#!/usr/bin/env groovy
/**
 * ==============================================================================
 * ecrPush — authenticate to ECR and push an image
 * ==============================================================================
 *
 * NOTE what is absent from this file: any AWS credential.
 *
 * The Jenkins EC2 instance carries an IAM instance profile
 * (02-Terraform/modules/server/main.tf) granting ecr:GetAuthorizationToken plus
 * push actions scoped to this project's three repositories. The AWS CLI picks
 * those temporary credentials up from the instance metadata service
 * automatically.
 *
 * The alternative — storing an AKIA… access key in Jenkins credentials — means
 * a long-lived secret that never rotates, is readable by anyone with Jenkins
 * script-console access, and is the single most common way a CI compromise
 * becomes an AWS account compromise.
 *
 * @param image     Full tagged reference to push
 * @param registry  '<account>.dkr.ecr.<region>.amazonaws.com'
 * @param region    AWS region
 * ==============================================================================
 */

def call(Map config) {

    String image    = config.image
    String registry = config.registry
    String region   = config.region ?: 'us-east-1'

    echo "Pushing ${image}"

    // --------------------------------------------------------------------------
    // Authenticate
    // --------------------------------------------------------------------------
    // The token is valid for 12 hours and is piped straight into `docker login`
    // via --password-stdin.
    //
    // Passing it as `--password <token>` instead would place the secret in the
    // process argument list, where any user on the box can read it from
    // `ps aux` and where Docker itself prints a warning about insecure usage.
    sh """
        set -eu
        aws ecr get-login-password --region ${region} \\
          | docker login --username AWS --password-stdin ${registry}
    """

    // --------------------------------------------------------------------------
    // Push
    // --------------------------------------------------------------------------
    // A transient network failure mid-push is common on large layers. Retrying
    // is safe because a Docker push is idempotent — already-uploaded layers are
    // skipped by digest.
    retry(3) {
        sh "docker push ${image}"
    }

    // --------------------------------------------------------------------------
    // Verify
    // --------------------------------------------------------------------------
    // Confirms the image is genuinely queryable in the registry. `docker push`
    // exiting 0 is good evidence but not proof that the manifest was committed.
    String repoName = image.substring(image.indexOf('/') + 1).split(':')[0]
    String imageTag = image.split(':').last()

    String digest = sh(
        script: """
            aws ecr describe-images \\
                --repository-name ${repoName} \\
                --image-ids imageTag=${imageTag} \\
                --region ${region} \\
                --query 'imageDetails[0].imageDigest' \\
                --output text
        """,
        returnStdout: true
    ).trim()

    // Always log out. The credential file at ~/.docker/config.json would
    // otherwise persist on the agent between builds.
    sh "docker logout ${registry} || true"

    echo "✅ Pushed ${image}\n   digest: ${digest}"

    return digest
}
