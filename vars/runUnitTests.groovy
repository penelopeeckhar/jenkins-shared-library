#!/usr/bin/env groovy
/**
 * ==============================================================================
 * runUnitTests — language-aware test execution
 * ==============================================================================
 *
 * Runs each service's tests with its native toolchain, INSIDE A CONTAINER.
 *
 * ------------------------------------------------------------------------------
 * Why containerised rather than on the agent
 * ------------------------------------------------------------------------------
 * The Jenkins server would otherwise need Node 22, Python 3.12 AND Maven with
 * JDK 21 installed simultaneously, kept in lockstep with whatever each
 * Dockerfile uses. They drift, and then "works in CI, fails in the image"
 * becomes a recurring class of bug.
 *
 * Running the tests in the same base image the Dockerfile uses guarantees the
 * test environment and the runtime environment are identical.
 *
 * ------------------------------------------------------------------------------
 * Note on the upstream application
 * ------------------------------------------------------------------------------
 * The source at Ibrahim-Adel15/iVolveFinalProject ships NO test suite. This step
 * is therefore written to:
 *   * run the tests when they exist, and FAIL the build when they fail
 *   * report clearly and continue when there are none
 *
 * It never silently passes a failing test, and it never blocks the pipeline over
 * a missing one. Adding tests to any service makes this step meaningful with no
 * pipeline change.
 *
 * @param language   'node' | 'python' | 'java'
 * @param sourceDir  Service directory
 * ==============================================================================
 */

def call(Map config) {

    String language  = config.language
    String sourceDir = config.sourceDir

    echo "Running ${language} unit tests in ${sourceDir}"

    switch (language) {

        // ----------------------------------------------------------------------
        // Node.js — frontend
        // ----------------------------------------------------------------------
        case 'node':
            // `jq -e` exits non-zero when the selector yields null/false, which
            // is exactly the "is there a test script?" question.
            int hasTests = sh(
                script: "jq -e '.scripts.test' ${sourceDir}/package.json > /dev/null 2>&1",
                returnStatus: true
            )

            if (hasTests == 0) {
                sh """
                    set -eu
                    docker run --rm \\
                        -v "\$(pwd)/${sourceDir}":/app \\
                        -w /app \\
                        node:22-alpine \\
                        sh -c 'npm ci --no-audit --no-fund || npm install --no-audit --no-fund; npm test'
                """
            } else {
                echo "⚠️  No 'test' script in ${sourceDir}/package.json — skipping."
                echo "    Add one with jest or vitest to enable this gate."
            }

            // Static syntax check. Cheap, and it catches a broken server.js
            // before a Docker build and a deploy discover it the hard way.
            sh """
                set -eu
                docker run --rm \\
                    -v "\$(pwd)/${sourceDir}":/app \\
                    -w /app \\
                    node:22-alpine \\
                    node --check server.js
                echo "✅ server.js parses cleanly"
            """
            break

        // ----------------------------------------------------------------------
        // Python — auth-service
        // ----------------------------------------------------------------------
        case 'python':
            int hasTests = sh(
                script: "ls ${sourceDir}/test_*.py >/dev/null 2>&1 || ls -d ${sourceDir}/tests >/dev/null 2>&1",
                returnStatus: true
            )

            if (hasTests == 0) {
                sh """
                    set -eu
                    docker run --rm \\
                        -v "\$(pwd)/${sourceDir}":/app \\
                        -w /app \\
                        python:3.12-slim \\
                        sh -c 'pip install --quiet -r requirements.txt pytest pytest-cov && \\
                               python -m pytest -v --junitxml=test-results/junit.xml'
                """
            } else {
                echo "⚠️  No test_*.py or tests/ directory in ${sourceDir} — skipping."
                echo "    Add pytest tests to enable this gate."
            }

            // Byte-compile the module. Catches syntax errors and bad imports
            // without needing a database.
            sh """
                set -eu
                docker run --rm \\
                    -v "\$(pwd)/${sourceDir}":/app \\
                    -w /app \\
                    python:3.12-slim \\
                    python -m py_compile app.py
                echo "✅ app.py compiles cleanly"
            """
            break

        // ----------------------------------------------------------------------
        // Java — roadmap-service
        // ----------------------------------------------------------------------
        case 'java':
            // Maven always has a `test` phase, so this runs unconditionally. It
            // is a no-op when src/test/java is empty, and surefire then produces
            // no report — which the junit step tolerates via allowEmptyResults.
            //
            // The ~/.m2 volume persists the dependency cache between builds.
            // Without it every build re-downloads the entire Spring Boot
            // dependency tree, adding several minutes.
            sh """
                set -eu
                docker run --rm \\
                    -v "\$(pwd)/${sourceDir}":/build \\
                    -v maven-repo:/root/.m2 \\
                    -w /build \\
                    maven:3.9.11-eclipse-temurin-21 \\
                    mvn -B test
            """
            echo "✅ Maven test phase completed"
            break

        default:
            error("runUnitTests: unsupported language '${language}'")
    }
}
