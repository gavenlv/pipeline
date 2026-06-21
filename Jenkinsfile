// Apex CI Library - self-test pipeline
// Runs the full test suite in CI to validate the library compiles, links, and behaves correctly.
// 2026-06: 改造成 Maven 项目后，直接调用 mvn 标准生命周期。

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

        stage('Package (optional)') {
            when {
                expression { env.PACKAGE == 'true' }
            }
            steps {
                script {
                    if (isUnix()) {
                        sh 'bash build.sh -package'
                    } else {
                        bat 'call build.bat -package'
                    }
                }
            }
        }

        stage('Archive') {
            steps {
                archiveArtifacts artifacts: 'target/apex-ci-library-*.jar,target/surefire-reports/**',
                                 allowEmptyArchive: true
            }
        }
    }

    post {
        always {
            junit testResults: 'target/surefire-reports/TEST-*.xml', allowEmptyResults: true
        }
    }
}
