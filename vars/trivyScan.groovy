#!/usr/bin/env groovy
/**
 * ==============================================================================
 * trivyScan — container image vulnerability gate
 * ==============================================================================
 *
 * This is the step that decides whether an image is allowed to reach the
 * registry. It runs Trivy twice against the same cached scan result:
 *
 *   Pass 1  --exit-code 0  → always succeeds; produces the human-readable
 *                             report and the JSON artefact
 *   Pass 2  --exit-code 1  → FAILS the build when a fixable CRITICAL is found
 *
 * Two passes rather than one because a single failing invocation aborts the
 * shell before the report is written, leaving nothing to look at when
 * diagnosing why the build was blocked.
 *
 * ------------------------------------------------------------------------------
 * Why --ignore-unfixed
 * ------------------------------------------------------------------------------
 * Without it, every build fails on CVEs in the base image for which NO patched
 * package exists yet. There is no action a developer can take, so the gate
 * becomes noise, and a gate everyone ignores or bypasses is worse than none.
 *
 * With it, the gate fires only on vulnerabilities that CAN be fixed by rebuilding
 * — which is exactly the actionable set.
 *
 * @param image           Image reference to scan
 * @param failOnCritical  When true, a fixable CRITICAL fails the build
 * @param reportPrefix    Filename prefix for the archived reports
 * ==============================================================================
 */

def call(Map config) {

    String image         = config.image
    boolean failOnCrit   = config.containsKey('failOnCritical') ? config.failOnCritical : true
    String prefix        = config.reportPrefix ?: 'scan'

    echo "Scanning ${image} with Trivy"

    // --------------------------------------------------------------------------
    // Pass 1 — reports (never fails)
    // --------------------------------------------------------------------------
    sh """
        set -eu

        # Human-readable table for the console and for the archived artefact.
        trivy image \\
            --scanners vuln \\
            --severity HIGH,CRITICAL \\
            --ignore-unfixed \\
            --exit-code 0 \\
            --format table \\
            --output ${prefix}-trivy-report.txt \\
            --cache-dir ${env.TRIVY_CACHE_DIR ?: '/var/cache/trivy'} \\
            --timeout 10m \\
            ${image}

        # Machine-readable JSON, for archiving and for any downstream tooling
        # (DefectDojo, a compliance dashboard, etc.).
        trivy image \\
            --scanners vuln \\
            --severity HIGH,CRITICAL \\
            --ignore-unfixed \\
            --exit-code 0 \\
            --format json \\
            --output ${prefix}-trivy-report.json \\
            --cache-dir ${env.TRIVY_CACHE_DIR ?: '/var/cache/trivy'} \\
            --timeout 10m \\
            ${image}

        echo "───────────────────── Trivy report ─────────────────────"
        cat ${prefix}-trivy-report.txt
        echo "────────────────────────────────────────────────────────"
    """

    // --------------------------------------------------------------------------
    // Summarise the counts
    // --------------------------------------------------------------------------
    // Parsed from the JSON so the numbers appear in the build description and
    // in any notification, without anyone having to open the artefact.
    String counts = sh(
        script: """
            jq -r '[.Results[]?.Vulnerabilities[]?] | group_by(.Severity) | map({(.[0].Severity): length}) | add // {}' \\
                ${prefix}-trivy-report.json 2>/dev/null || echo '{}'
        """,
        returnStdout: true
    ).trim()

    echo "Vulnerability counts (fixable only): ${counts}"

    // --------------------------------------------------------------------------
    // Pass 2 — the gate
    // --------------------------------------------------------------------------
    if (failOnCrit) {
        int rc = sh(
            script: """
                trivy image \\
                    --scanners vuln \\
                    --severity CRITICAL \\
                    --ignore-unfixed \\
                    --exit-code 1 \\
                    --format table \\
                    --cache-dir ${env.TRIVY_CACHE_DIR ?: '/var/cache/trivy'} \\
                    --timeout 10m \\
                    ${image}
            """,
            // returnStatus rather than letting a non-zero exit abort the step,
            // so the failure can be reported with a useful message.
            returnStatus: true
        )

        if (rc != 0) {
            error("""
                ❌ SECURITY GATE FAILED

                ${image} contains CRITICAL vulnerabilities that have a fix available.
                The image was NOT pushed to ECR.

                To resolve:
                  1. Read the full report in the archived artefacts of this build.
                  2. Update the base image tag in the service's Dockerfile, or
                     bump the affected dependency (package.json / requirements.txt / pom.xml).
                  3. Re-run the build.

                To ship despite this — a deliberate, documented risk decision —
                set failOnCritical: false in the service's Jenkinsfile.
            """.stripIndent())
        }

        echo "✅ Security gate passed — no fixable CRITICAL vulnerabilities."
    } else {
        echo "⚠️  Security gate is DISABLED for this service (failOnCritical: false)."
    }

    return counts
}
