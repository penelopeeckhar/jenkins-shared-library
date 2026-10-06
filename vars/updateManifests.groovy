#!/usr/bin/env groovy
/**
 * ==============================================================================
 * updateManifests — rewrite the image tag in the GitOps manifests
 * ==============================================================================
 *
 * Stage 8 of the pipeline. Edits kustomization.yaml so it points at the image
 * this build just pushed, then proves the result still renders.
 *
 * This step performs NO Git operations. Committing and pushing is the separate
 * responsibility of `pushManifests` (stage 9), which keeps the two halves of the
 * brief's "Update Manifests" / "Push Manifests" requirement independently
 * visible in the Jenkins stage view — and, more usefully, means a failed render
 * fails BEFORE anything reaches Git.
 *
 * ------------------------------------------------------------------------------
 * Why `kustomize edit set image` and not sed
 * ------------------------------------------------------------------------------
 * The naive approach is:
 *
 *     sed -i 's|image: .*|image: NEW|g' 05-auth-service.yaml
 *
 * That regex matches EVERY line beginning with `image:`. Verified against this
 * repository's actual rendered output, there are 5 image references:
 *
 *     ivolve-auth-service      ← should be replaced
 *     ivolve-frontend          ← should be replaced
 *     ivolve-roadmap-service   ← should be replaced
 *     busybox:1.36             ← init container — MUST NOT be replaced
 *     mysql:8.0                ← the database  — MUST NOT be replaced
 *
 * Kustomize rewrites exactly the 3 it is told to. `sed` would rewrite all 5,
 * silently replacing the database with an application image. The failure appears
 * at deploy time as an inexplicable CrashLoopBackOff with no obvious cause.
 *
 * Kustomize also edits kustomization.yaml rather than the manifests themselves,
 * so a release diff is three lines instead of whole-file churn.
 *
 * @param manifestDir  Directory containing kustomization.yaml
 * @param imageName    Kustomize image key, e.g. 'ivolve-frontend'
 * @param newImage     Full new reference '<registry>/<name>:<tag>'
 *
 * @return true if the manifest changed, false if it was already up to date.
 *         `pushManifests` uses this to skip a no-op commit.
 * ==============================================================================
 */

def call(Map config) {

    String manifestDir = config.manifestDir
    String imageName   = config.imageName
    String newImage    = config.newImage

    if (!manifestDir || !imageName || !newImage) {
        error("updateManifests: manifestDir, imageName and newImage are all required")
    }

    if (!fileExists("${manifestDir}/kustomization.yaml")) {
        error("""
            updateManifests: no kustomization.yaml found in ${manifestDir}

            The GitOps directory must be a Kustomize root. Check that
            manifestDir is correct and that kustomization.yaml is committed.
        """.stripIndent())
    }

    boolean changed = false

    dir(manifestDir) {

        // Capture the current state so the change can be reported accurately and
        // so a no-op rebuild can be detected without invoking Git.
        String before = sh(
            script: "grep -A2 'name: ${imageName}\$' kustomization.yaml || true",
            returnStdout: true
        ).trim()

        sh """
            set -eu

            echo "── kustomization.yaml BEFORE ──"
            sed -n '/^images:/,/^[a-z]/p' kustomization.yaml | head -20

            # `kustomize edit set image <key>=<newRef>`
            # kubectl 1.14+ bundles kustomize, so no separate binary is required.
            kustomize edit set image ${imageName}=${newImage}

            echo "── kustomization.yaml AFTER ──"
            sed -n '/^images:/,/^[a-z]/p' kustomization.yaml | head -20
        """

        String after = sh(
            script: "grep -A2 'name: ${imageName}\$' kustomization.yaml || true",
            returnStdout: true
        ).trim()

        changed = (before != after)

        // Render the FULL output before anything is committed. This catches a
        // malformed kustomization here, in a failed build, rather than in Git —
        // where ArgoCD would mark the entire Application unhealthy and block
        // every other service's deployment, not just this one.
        sh "kubectl kustomize . > /dev/null && echo '✅ kustomize build succeeds'"
    }

    if (changed) {
        echo "✅ Manifest updated: ${imageName} → ${newImage}"
    } else {
        echo "ℹ️  Manifest already points at ${newImage} — no change."
    }

    // Recorded on the environment so `pushManifests` can skip a no-op commit
    // even when the two stages are invoked independently.
    env.MANIFEST_CHANGED = changed.toString()

    return changed
}
