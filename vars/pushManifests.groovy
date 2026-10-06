#!/usr/bin/env groovy
/**
 * ==============================================================================
 * pushManifests — commit and push the GitOps change
 * ==============================================================================
 *
 * Stage 9, the final stage, and the actual CI → CD handoff.
 *
 * This commit is the ONLY thing that triggers a deployment. Jenkins never runs
 * `kubectl apply`: it writes the desired state to Git, and ArgoCD — which is
 * watching this path — reconciles the cluster to match.
 *
 * That separation is the whole point of GitOps:
 *
 *     * Jenkins needs NO cluster credentials to deploy.
 *     * Every deployment is a reviewable, revertible Git commit.
 *     * `git revert` is a rollback.
 *     * The cluster's desired state is readable without cluster access.
 *
 * Split from `updateManifests` (stage 8) so the brief's "Update Manifests" and
 * "Push Manifests" stages are independently visible in the Jenkins stage view,
 * and so a render failure aborts before anything reaches Git.
 *
 * @param manifestDir  Directory containing the edited kustomization.yaml
 * @param imageName    Service name, for the commit message
 * @param newImage     Full image reference, for the commit message
 * @param gitRepo      'github.com/User/Repo.git' — NO scheme
 * @param gitBranch    Target branch (default: main)
 * @param buildNumber  For the commit message
 * @param commitShort  Source commit SHA, for traceability
 * @param credentialsId  Jenkins credential holding the Git PAT
 * ==============================================================================
 */

def call(Map config) {

    String manifestDir   = config.manifestDir
    String imageName     = config.imageName
    String newImage      = config.newImage
    String gitRepo       = config.gitRepo
    String gitBranch     = config.gitBranch ?: 'main'
    String buildNumber   = config.buildNumber ?: '0'
    String commitShort   = config.commitShort ?: 'unknown'
    String credentialsId = config.credentialsId ?: 'github-token'

    if (!manifestDir || !gitRepo) {
        error("pushManifests: manifestDir and gitRepo are required")
    }

    // `gitRepo` is concatenated after https://user:token@ — a scheme here would
    // produce https://user:token@https://github.com/... and fail confusingly.
    if (gitRepo.startsWith('http')) {
        error("pushManifests: gitRepo must NOT include a scheme. Use 'github.com/User/Repo.git'.")
    }

    // Skip cleanly when stage 8 reported no change. A rebuild of an unchanged
    // commit is a legitimate operation, not a failure.
    if (env.MANIFEST_CHANGED == 'false') {
        echo "ℹ️  No manifest change to push — skipping commit."
        return
    }

    withCredentials([usernamePassword(
        credentialsId: credentialsId,
        usernameVariable: 'GIT_USERNAME',
        // The "password" is a fine-grained Personal Access Token scoped to
        // Contents: Read and write on THIS repository only.
        passwordVariable: 'GIT_TOKEN'
    )]) {

        sh '''
            set -eu
            git config user.email "jenkins@ivolve.io"
            git config user.name  "Jenkins CI"
        '''

        sh """
            set -eu

            git add ${manifestDir}/kustomization.yaml

            # `git diff --cached --quiet` exits 0 when nothing is staged.
            # A second guard beyond MANIFEST_CHANGED, in case this step is
            # called standalone from a custom pipeline.
            if git diff --cached --quiet; then
                echo "Nothing staged — skipping commit."
                exit 0
            fi

            # [skip ci] stops this commit from triggering the very pipeline that
            # created it. Without it, each build pushes a commit that starts
            # another build — an infinite loop that runs until someone notices
            # the executor is permanently busy.
            git commit -m "ci(${imageName}): deploy ${newImage}

            Build:  #${buildNumber}
            Source: ${commitShort}

            Automated by Jenkins. ArgoCD will reconcile the cluster to this revision.

            [skip ci]"
        """

        // Rebase-and-retry. Three services push to the same branch, so two
        // pipelines finishing close together race, and the loser is rejected as
        // non-fast-forward. Rebasing onto the winner's commit and retrying
        // resolves it with no human intervention.
        //
        // The credential is interpolated by the SHELL from an environment
        // variable (note the single-quoted heredoc), NOT by Groovy. Groovy
        // interpolation ("${GIT_TOKEN}") would embed the secret into the script
        // Jenkins writes to disk and echoes into the console log.
        retry(3) {
            sh '''
                set -eu
                git pull --rebase origin ''' + gitBranch + ''' || true
                git push https://${GIT_USERNAME}:${GIT_TOKEN}@''' + gitRepo + ''' HEAD:''' + gitBranch + '''
            '''
        }
    }

    echo """
    ✅ Manifests pushed to ${gitBranch}
       ${imageName} → ${newImage}
       ArgoCD will detect this commit and sync the cluster.
    """.stripIndent()
}
