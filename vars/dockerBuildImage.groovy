#!/usr/bin/env groovy
/**
 * ==============================================================================
 * dockerBuildImage — build a container image with provenance labels
 * ==============================================================================
 *
 * @param image        Full tagged reference, e.g. '123.dkr.ecr…/ivolve-frontend:42-a1b2c3d'
 * @param context      Build context directory, e.g. 'src/frontend'
 * @param commit       Full git SHA, recorded as an OCI label
 * @param buildNumber  Jenkins build number, recorded as an OCI label
 * ==============================================================================
 */

def call(Map config) {

    String image       = config.image
    String context     = config.context
    String commit      = config.commit ?: 'unknown'
    String buildNumber = config.buildNumber ?: '0'

    // Fail early with a clear message rather than letting `docker build` emit
    // "unable to prepare context: path not found", which does not say which
    // parameter was wrong.
    if (!fileExists("${context}/Dockerfile")) {
        error("dockerBuildImage: no Dockerfile at ${context}/Dockerfile")
    }

    echo "Building ${image} from ${context}"

    // --------------------------------------------------------------------------
    // OCI provenance labels
    // --------------------------------------------------------------------------
    // Injected at build time rather than written into the Dockerfile, because
    // they change on every build and baking them in would invalidate the layer
    // cache for every preceding instruction.
    //
    // Their value is traceability: given only a running container in the
    // cluster, `docker inspect` answers "which commit is this, and which build
    // produced it?" — the question you always need answered during an incident.
    String buildDate = sh(script: 'date -u +%Y-%m-%dT%H:%M:%SZ', returnStdout: true).trim()

    sh """
        set -eu

        docker build \\
            --file ${context}/Dockerfile \\
            --tag  ${image} \\
            --label org.opencontainers.image.revision='${commit}' \\
            --label org.opencontainers.image.created='${buildDate}' \\
            --label org.opencontainers.image.version='${buildNumber}' \\
            --label org.opencontainers.image.source='${env.GIT_URL ?: ""}' \\
            --label io.jenkins.build-url='${env.BUILD_URL ?: ""}' \\
            ${context}
    """

    // Report the image size. A sudden jump usually means a multi-stage build was
    // broken — for example a COPY that accidentally pulled in the build stage —
    // and catching it here is far cheaper than discovering it in ECR bills.
    String size = sh(
        script: "docker image inspect ${image} --format '{{.Size}}' | numfmt --to=iec",
        returnStdout: true
    ).trim()

    echo "✅ Built ${image} (${size})"

    return image
}
