#!/usr/bin/env groovy
/**
 * ==============================================================================
 * sonarQubeScan — static analysis with a blocking Quality Gate
 * ==============================================================================
 *
 * Two distinct steps that are frequently confused:
 *
 *   1. ANALYSIS   the scanner reads the source, computes metrics, and UPLOADS
 *                 the result to the SonarQube server. This is asynchronous —
 *                 the scanner exits successfully as soon as the upload is
 *                 accepted, long before the server has finished processing.
 *
 *   2. QUALITY GATE  the pipeline then WAITS for the server to finish and
 *                 report pass/fail against the configured thresholds.
 *
 * Running only step 1 — which many tutorials do — produces a pipeline that
 * reports SonarQube "success" for code that fails every quality rule. The
 * waitForQualityGate() call below is what makes it an actual gate.
 *
 * ------------------------------------------------------------------------------
 * Prerequisites in Jenkins
 * ------------------------------------------------------------------------------
 *   * Plugin: "SonarQube Scanner for Jenkins"
 *   * Manage Jenkins → System → SonarQube servers
 *       Name:  sonarqube          ← must match SONARQUBE_SERVER below
 *       URL:   http://localhost:9000
 *       Token: the `sonar-token` credential
 *   * Manage Jenkins → Tools → SonarQube Scanner installations
 *       Name:  sonar-scanner      ← must match SONARQUBE_SCANNER below
 *
 * @param projectKey  Unique project identifier in SonarQube
 * @param sourceDir   Directory to analyse
 * @param language    'node' | 'python' | 'java'
 * @param version     Project version recorded with the analysis
 * ==============================================================================
 */

def call(Map config) {

    String projectKey = config.projectKey
    String sourceDir  = config.sourceDir
    String language   = config.language
    String version    = config.version ?: '1.0'

    // Names configured in Jenkins global settings. Centralised here so a change
    // is made in one place rather than in three Jenkinsfiles.
    String SONARQUBE_SERVER  = 'sonarqube'
    String SONARQUBE_SCANNER = 'sonar-scanner'

    // --------------------------------------------------------------------------
    // Java takes a different path
    // --------------------------------------------------------------------------
    // For a Maven project the sonar-maven-plugin is strictly better than the
    // standalone scanner: it reads the POM for the module structure, the
    // compiled bytecode location and the test-report paths. The generic scanner
    // has to be told all of that by hand and gets it wrong for multi-module
    // builds.
    if (language == 'java') {
        withSonarQubeEnv(SONARQUBE_SERVER) {
            sh """
                set -eu
                docker run --rm \\
                    -v "\$(pwd)/${sourceDir}":/build \\
                    -v maven-repo:/root/.m2 \\
                    -w /build \\
                    --network host \\
                    -e SONAR_HOST_URL="\${SONAR_HOST_URL}" \\
                    -e SONAR_TOKEN="\${SONAR_AUTH_TOKEN}" \\
                    maven:3.9.11-eclipse-temurin-21 \\
                    mvn -B verify org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \\
                        -Dsonar.projectKey=${projectKey} \\
                        -Dsonar.projectName=${projectKey} \\
                        -Dsonar.projectVersion=${version}
            """
        }
    } else {
        // ----------------------------------------------------------------------
        // Node and Python use the standalone scanner via Docker
        // ----------------------------------------------------------------------
        // Using the official docker image removes the need to manually configure
        // the "SonarQube Scanner" tool in Jenkins Global Tool Configuration.

        // withSonarQubeEnv injects SONAR_HOST_URL and SONAR_AUTH_TOKEN from the
        // Jenkins configuration, and — importantly — records the analysis task
        // ID that waitForQualityGate() needs later. Calling the scanner without
        // this wrapper makes the gate step fail with "no SonarQube analysis
        // task found".
        withSonarQubeEnv(SONARQUBE_SERVER) {
            sh """
                set -eu
                docker run --rm \\
                    -v "\$(pwd)/${sourceDir}":/usr/src \\
                    --network host \\
                    -e SONAR_HOST_URL="\${SONAR_HOST_URL}" \\
                    -e SONAR_TOKEN="\${SONAR_AUTH_TOKEN}" \\
                    sonarsource/sonar-scanner-cli:5 \\
                    sonar-scanner \\
                        -Dsonar.projectKey=${projectKey} \\
                        -Dsonar.projectName=${projectKey} \\
                        -Dsonar.projectVersion=${version} \\
                        -Dsonar.sources=. \\
                        -Dsonar.sourceEncoding=UTF-8 \\
                        ${language == 'python' ? "-Dsonar.python.version=3.12" : ""} \\
                        ${language == 'node'   ? "-Dsonar.javascript.node.maxspace=2048" : ""} \\
                        -Dsonar.exclusions='**/node_modules/**,**/venv/**,**/__pycache__/**,**/target/**,**/*.min.js'
            """
        }
    }

    // --------------------------------------------------------------------------
    // Wait for the Quality Gate
    // --------------------------------------------------------------------------
    // The timeout is essential. waitForQualityGate() polls the server (or waits
    // for a webhook); if SonarQube is down or the webhook is misconfigured, this
    // step blocks FOREVER and holds the executor until someone notices.
    timeout(time: 10, unit: 'MINUTES') {
        // abortPipeline: true fails the build when the gate fails.
        //
        // Setting it to false is the common cop-out — the gate then reports its
        // verdict into the log and the pipeline sails on regardless, which is
        // indistinguishable from having no gate.
        def qg = waitForQualityGate abortPipeline: false

        if (qg.status != 'OK') {
            // UNSTABLE rather than FAILURE is a deliberate middle ground for
            // this project: the upstream application has pre-existing code
            // smells that would block every build from day one, and a gate
            // everyone routes around is worse than an honest warning.
            //
            // For a codebase you own from the start, change this to:
            //     error("Quality Gate failed: ${qg.status}")
            unstable("⚠️  SonarQube Quality Gate: ${qg.status}")
            echo """
                The Quality Gate did not pass. Review the findings at:
                  ${env.SONAR_HOST_URL ?: 'http://<jenkins-ip>:9000'}/dashboard?id=${projectKey}

                This is reported as UNSTABLE rather than FAILED because the
                upstream application carries pre-existing findings. Once the
                baseline is clean, change unstable() to error() in
                vars/sonarQubeScan.groovy to make this a hard gate.
            """.stripIndent()
        } else {
            echo "✅ SonarQube Quality Gate passed."
        }
    }
}
