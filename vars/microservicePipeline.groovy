#!/usr/bin/env groovy
/**
 * ==============================================================================
 * microservicePipeline — the shared CI pipeline for every iVolve microservice
 * ==============================================================================
 *
 * One definition, three consumers. Each Jenkinsfile in 05-Jenkins/Jenkinsfiles/
 * is ~10 lines of configuration; ALL the logic lives here. Fixing a bug or
 * adding a stage happens once and every service inherits it immediately.
 *
 * ------------------------------------------------------------------------------
 * Pipeline stages
 * ------------------------------------------------------------------------------
 *   1. Checkout          resolve the commit and derive an immutable image tag
 *   2. Unit Tests        run the service's own test suite (language-aware)
 *   3. SonarQube         static analysis + block on the Quality Gate
 *   4. Build Image       docker build
 *   5. Scan Image        Trivy — FAILS the build on a fixable CRITICAL CVE
 *   6. Push Image        authenticate to ECR and push
 *   7. Delete Image      remove the local copy to reclaim disk
 *   8. Update Manifests  `kustomize edit set image` in the GitOps directory
 *   9. Push Manifests    commit and push, which is what triggers ArgoCD
 *
 * Stages 4-9 are the six required by the project brief; 1-3 are the additions
 * that make it a real pipeline rather than a build script.
 *
 * ------------------------------------------------------------------------------
 * Parameters
 * ------------------------------------------------------------------------------
 * @param serviceName   Required. Directory under src/ AND the ECR repository
 *                      name, e.g. 'ivolve-frontend'.
 * @param sourceDir     Required. Build context, e.g. 'src/frontend'.
 * @param language      Required. 'node' | 'python' | 'java' — selects the unit
 *                      test and SonarQube strategy.
 * @param ecrRegistry   Required. '<account>.dkr.ecr.<region>.amazonaws.com'.
 * @param awsRegion     Optional. Defaults to 'us-east-1'.
 * @param gitRepo       Required. Repo host/path for the manifest push, without
 *                      the scheme, e.g. 'github.com/User/CloudDevOpsProject.git'.
 * @param gitBranch     Optional. Defaults to 'main'.
 * @param manifestDir   Optional. Defaults to '04-Kubernetes/manifests'.
 * @param runSonar      Optional. Defaults to true.
 * @param failOnCritical Optional. Defaults to true — the security gate.
 * ==============================================================================
 */

def call(Map config) {

    // --------------------------------------------------------------------------
    // Validate the caller's configuration BEFORE the pipeline starts
    // --------------------------------------------------------------------------
    // Failing here produces one clear message. Without it, a missing key
    // surfaces 15 minutes later as a Groovy NullPointerException inside a shell
    // step, with a stack trace that points at the library rather than at the
    // Jenkinsfile that is actually wrong.
    List required = ['serviceName', 'sourceDir', 'language', 'ecrRegistry', 'gitRepo']
    List missing  = required.findAll { !config.containsKey(it) || !config[it] }
    if (missing) {
        error("microservicePipeline: missing required parameter(s): ${missing.join(', ')}")
    }
    if (!(config.language in ['node', 'python', 'java'])) {
        error("microservicePipeline: language must be one of node|python|java, got '${config.language}'")
    }

    // Defaults for everything optional, applied once so the rest of the file can
    // read config.* without repeating `?:` fallbacks.
    config.awsRegion      = config.awsRegion      ?: 'us-east-1'
    config.gitBranch      = config.gitBranch      ?: 'main'
    config.manifestDir    = config.manifestDir    ?: '04-Kubernetes/manifests'
    config.runSonar       = config.containsKey('runSonar')       ? config.runSonar       : true
    config.failOnCritical = config.containsKey('failOnCritical') ? config.failOnCritical : true

    pipeline {

        // `agent any` runs on the built-in node. For a real multi-team
        // controller, use a label to route builds to a dedicated agent:
        //     agent { label 'docker && linux' }
        // See 05-Jenkins/README.md for the agent setup.
        agent any

        // ----------------------------------------------------------------------
        // Options & Triggers
        // ----------------------------------------------------------------------
        triggers {
            // Automatically triggers the pipeline when a GitHub webhook is received
            githubPush()
        }

        options {
            // Cap retained history. Without this, Jenkins keeps every build
            // forever and JENKINS_HOME grows until the disk fills — the most
            // common cause of a Jenkins controller that "suddenly broke".
            buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))

            // A hung docker pull or Maven download would otherwise occupy the
            // executor indefinitely and block every other job.
            timeout(time: 45, unit: 'MINUTES')

            // Two concurrent runs of the same job would race on the shared
            // Docker daemon and, worse, both try to push the manifest commit —
            // producing a non-fast-forward rejection.
            disableConcurrentBuilds()

            // Prefix every console line with a timestamp. Essential for
            // answering "which stage was slow?" after the fact.
            timestamps()

            // Render ANSI colour from Maven/npm/Trivy instead of raw escapes.
            ansiColor('xterm')

            // The explicit checkout in stage 1 does the SCM clone.
            skipDefaultCheckout(true)
        }

        // ----------------------------------------------------------------------
        // Environment
        // ----------------------------------------------------------------------
        environment {
            SERVICE_NAME = "${config.serviceName}"
            SOURCE_DIR   = "${config.sourceDir}"
            ECR_REGISTRY = "${config.ecrRegistry}"
            AWS_REGION   = "${config.awsRegion}"
            AWS_DEFAULT_REGION = "${config.awsRegion}"
            MANIFEST_DIR = "${config.manifestDir}"

            // Shared Trivy cache, pre-warmed by the Ansible trivy role, so a
            // build does not re-download the ~600 MB vulnerability database.
            TRIVY_CACHE_DIR = '/var/cache/trivy'

            // Keeps `docker build` from printing the interactive TTY progress
            // bar, which renders as thousands of unreadable lines in a log.
            DOCKER_BUILDKIT = '1'
            BUILDKIT_PROGRESS = 'plain'
        }

        stages {

            // ------------------------------------------------------------------
            // 1. Checkout
            // ------------------------------------------------------------------
            stage('Checkout') {
                steps {
                    script {
                        cleanWs()
                        def scmVars = checkout scm

                        // Derive an IMMUTABLE, traceable tag.
                        //
                        // The obvious choice, ${BUILD_NUMBER} alone, tells you
                        // nothing about WHAT was built. Combining it with the
                        // short commit SHA means the running image in the
                        // cluster maps back to an exact line of source:
                        //     ivolve-frontend:42-a1b2c3d
                        //
                        // Never :latest — a moving tag makes rollback
                        // impossible and defeats the ECR immutability set in
                        // Terraform.
                        env.GIT_COMMIT_FULL  = scmVars.GIT_COMMIT
                        env.GIT_COMMIT_SHORT = scmVars.GIT_COMMIT.take(7)
                        env.IMAGE_TAG        = "${env.BUILD_NUMBER}-${env.GIT_COMMIT_SHORT}"
                        env.IMAGE_NAME       = "${env.ECR_REGISTRY}/${env.SERVICE_NAME}"
                        env.FULL_IMAGE       = "${env.IMAGE_NAME}:${env.IMAGE_TAG}"

                        // Surface the version in the Jenkins UI so the build
                        // list is readable at a glance.
                        currentBuild.displayName = "#${env.BUILD_NUMBER} · ${env.SERVICE_NAME} · ${env.GIT_COMMIT_SHORT}"
                        currentBuild.description = "Building ${env.FULL_IMAGE}"

                        echo """
                        ╔════════════════════════════════════════════════════════╗
                          Service : ${env.SERVICE_NAME}
                          Source  : ${env.SOURCE_DIR}
                          Commit  : ${env.GIT_COMMIT_SHORT}
                          Image   : ${env.FULL_IMAGE}
                        ╚════════════════════════════════════════════════════════╝
                        """.stripIndent()
                    }
                }
            }

            // ------------------------------------------------------------------
            // 2. Unit Tests
            // ------------------------------------------------------------------
            // Runs BEFORE the image is built. Failing here saves the several
            // minutes a Docker build would take, and — more importantly —
            // guarantees no image is ever produced from code that fails its
            // own tests.
            stage('Unit Tests') {
                steps {
                    script {
                        runUnitTests(
                            language:  config.language,
                            sourceDir: env.SOURCE_DIR
                        )
                    }
                }
                post {
                    always {
                        // Publish whatever reports exist. allowEmptyResults is
                        // required because the upstream application ships no
                        // tests yet — a missing report should not fail the build,
                        // but a FAILING test must.
                        junit allowEmptyResults: true,
                              testResults: "${env.SOURCE_DIR}/**/target/surefire-reports/*.xml, ${env.SOURCE_DIR}/**/test-results/**/*.xml"
                    }
                }
            }

            // ------------------------------------------------------------------
            // 3. SonarQube analysis + Quality Gate
            // ------------------------------------------------------------------
            stage('SonarQube Analysis') {
                when {
                    // `expression` evaluates Groovy; beforeAgent avoids
                    // allocating a node when the stage will be skipped.
                    beforeAgent true
                    expression { return config.runSonar }
                }
                steps {
                    script {
                        sonarQubeScan(
                            projectKey: env.SERVICE_NAME,
                            sourceDir:  env.SOURCE_DIR,
                            language:   config.language,
                            version:    env.IMAGE_TAG
                        )
                    }
                }
            }

            // ------------------------------------------------------------------
            // 4. Build Image
            // ------------------------------------------------------------------
            stage('Build Image') {
                steps {
                    script {
                        dockerBuildImage(
                            image:      env.FULL_IMAGE,
                            context:    env.SOURCE_DIR,
                            commit:     env.GIT_COMMIT_FULL,
                            buildNumber: env.BUILD_NUMBER
                        )
                    }
                }
            }

            // ------------------------------------------------------------------
            // 5. Scan Image — the security gate
            // ------------------------------------------------------------------
            // Deliberately placed BEFORE the push. Scanning after pushing means
            // the vulnerable image is already in the registry and could be
            // pulled by anything watching the repository — the gate has to be
            // upstream of publication to be a gate at all.
            stage('Scan Image') {
                steps {
                    script {
                        trivyScan(
                            image:          env.FULL_IMAGE,
                            failOnCritical: config.failOnCritical,
                            reportPrefix:   env.SERVICE_NAME
                        )
                    }
                }
                post {
                    always {
                        archiveArtifacts artifacts: 'trivy-*.json, trivy-*.txt',
                                         allowEmptyArchive: true,
                                         fingerprint: true
                    }
                }
            }

            // ------------------------------------------------------------------
            // 6. Push Image
            // ------------------------------------------------------------------
            stage('Push Image') {
                steps {
                    script {
                        ecrPush(
                            image:    env.FULL_IMAGE,
                            registry: env.ECR_REGISTRY,
                            region:   env.AWS_REGION
                        )
                    }
                }
            }

            // ------------------------------------------------------------------
            // 7. Delete Image Locally
            // ------------------------------------------------------------------
            // The Jenkins server has a finite disk. Three services × ~200-600 MB
            // per build, retained across 20 builds, exhausts a 50 GB volume in
            // days. The image is safe in ECR at this point.
            stage('Delete Image Locally') {
                steps {
                    script {
                        // `|| true` because a missing image is the desired end
                        // state — failing the build for it would be perverse.
                        sh """
                            echo "Removing local image ${env.FULL_IMAGE}"
                            docker rmi -f ${env.FULL_IMAGE} || true
                            docker image prune -f --filter 'until=24h' || true
                        """
                        sh 'docker system df'
                    }
                }
            }

            // ------------------------------------------------------------------
            // 8 & 9. Update and Push Manifests — the GitOps handoff
            // ------------------------------------------------------------------
            // This is where CI ends and CD begins. Jenkins does NOT deploy: it
            // commits the new image tag to Git, and ArgoCD notices the change
            // and reconciles the cluster.
            //
            // Git therefore remains the single source of truth for what is
            // running, which is the whole premise of GitOps.
            // ------------------------------------------------------------------
            // Stage 8 — Update Manifests
            // ------------------------------------------------------------------
            // Rewrites the image tag with `kustomize edit set image` and proves
            // the result still renders. NO Git operations happen here, so a
            // malformed kustomization fails the build BEFORE it reaches Git —
            // where ArgoCD would mark the whole Application unhealthy and block
            // every other service, not just this one.
            stage('Update Manifests') {
                steps {
                    script {
                        updateManifests(
                            manifestDir: env.MANIFEST_DIR,
                            imageName:   env.SERVICE_NAME,
                            newImage:    env.FULL_IMAGE
                        )
                    }
                }
            }

            // ------------------------------------------------------------------
            // Stage 9 — Push Manifests  (the CI → CD handoff)
            // ------------------------------------------------------------------
            // This commit is the ONLY thing that triggers a deployment. Jenkins
            // never runs `kubectl apply`: it writes the desired state to Git and
            // ArgoCD reconciles the cluster to match.
            stage('Push Manifests') {
                steps {
                    script {
                        pushManifests(
                            manifestDir: env.MANIFEST_DIR,
                            imageName:   env.SERVICE_NAME,
                            newImage:    env.FULL_IMAGE,
                            gitRepo:     config.gitRepo,
                            gitBranch:   config.gitBranch,
                            buildNumber: env.BUILD_NUMBER,
                            commitShort: env.GIT_COMMIT_SHORT
                        )
                    }
                }
            }
        }

        // ----------------------------------------------------------------------
        // Post actions
        // ----------------------------------------------------------------------
        post {
            success {
                script {
                    echo """
                    ✅ SUCCESS — ${env.SERVICE_NAME}
                       Image pushed : ${env.FULL_IMAGE}
                       ArgoCD will now sync the cluster to this revision.
                    """.stripIndent()
                }
            }

            failure {
                script {
                    echo "❌ FAILED — ${env.SERVICE_NAME} at stage '${env.STAGE_NAME}'"
                }
            }

            unstable {
                echo "⚠️  UNSTABLE — ${env.SERVICE_NAME}: tests or quality gate reported problems."
            }

            always {
                script {
                    // Belt and braces: if the pipeline failed BEFORE the
                    // 'Delete Image Locally' stage, the image is still on disk.
                    sh """
                        docker rmi -f ${env.FULL_IMAGE ?: 'nonexistent'} 2>/dev/null || true
                    """
                    // Always free the workspace. A failed build's workspace is
                    // otherwise retained indefinitely, and these contain full
                    // node_modules / .m2 trees.
                    cleanWs(
                        deleteDirs: true,
                        notFailBuild: true,
                        cleanWhenAborted: true,
                        cleanWhenFailure: true,
                        cleanWhenSuccess: true
                    )
                }
            }
        }
    }
}
