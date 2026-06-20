// Apex CI Library - self-test pipeline
// Runs the full test suite in CI to validate the library compiles, links, and behaves correctly.
// 轻量版：直接用 Jenkins 原生 pipeline 块，不再套自定义 apex.pipeline 抽象。

@Library('apex-ci-library@main') _

pipeline {
    agent any

    options {
        timeout(time: 30, unit: 'MINUTES')
        ansiColor('xterm')
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Compile + Test') {
            steps {
                script {
                    if (isUnix()) {
                        sh 'bash build.sh'
                    } else {
                        bat 'call build.bat'
                    }
                }
            }
        }

        stage('Archive') {
            steps {
                archiveArtifacts artifacts: 'build/classes/**,build/test-reports/**',
                                 allowEmptyArchive: true
            }
        }
    }
}
