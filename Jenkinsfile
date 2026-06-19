// Apex CI Library - self-test pipeline
// Runs the full test suite in CI to validate the library compiles, links, and behaves correctly.

@Library('apex-ci-library@main') _

apex.pipeline {
    stage('Checkout') {
        checkout scm
    }

    stage('Test') {
        // Run the JUnit suite via build.sh
        if (isUnix()) {
            sh 'bash build.sh test'
        } else {
            bat 'call build.bat test'
        }
    }

    stage('Archive') {
        // Archive the build output for debugging
        archiveArtifacts artifacts: 'build/classes/**,build/test-reports/**',
                         allowEmptyArchive: true
    }
}
