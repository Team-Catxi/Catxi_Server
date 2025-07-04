pipeline {
    agent {
        label 'jenkins_agent'
    }

    environment {
        // 시간대 설정 - Jenkins 파이프라인 레벨
        TZ = 'Asia/Seoul'
        // JVM 시간대 옵션
        JAVA_OPTS = '-Duser.timezone=Asia/Seoul -Dorg.apache.commons.jelly.tags.fmt.timeZone=Asia/Seoul'
        
        // Discord Webhook URL
        WEBHOOK_URL = credentials('DISCORD_CATXI')
        // Branch name
        BRANCH_NAME = 'main'
        // Spring Boot Profile
        SPRING_PROFILES_ACTIVE = 'dev'
        // EC2 User
        CATXI_USER = 'deploy'
        // EC2 IP 주소들 (환경변수로 관리)
        CATXI_EC2_1_IP = "${env.EC2_1_IP}"
        CATXI_EC2_2_IP = "${env.EC2_2_IP}"
        CATXI_EC2_3_IP = "${env.EC2_3_IP}"
        // SSH 포트
        CATXI_PORT = "22"
    }

    tools {
        jdk 'jdk17'
        gradle 'catxi_gradle'
    }

    options {
        timestamps()
        // 병렬 처리 중 하나라도 실패하면 모두 중단
        parallelsAlwaysFailFast()
    }

    stages {
        stage('Checkout Backend Repository') {
            steps {
                echo 'Checking out Backend Repository (main branch)'
                deleteDir()
                git branch: "${BRANCH_NAME}", url: 'https://github.com/Team-Catxi/Catxi_BackEnd'
            }
        }

        stage('Gradle Clean Build') {
            steps {
                script {
                    try {
                        echo 'Building Backend with Asia/Seoul timezone'
                        sh '''
                            # 시간대 확인
                            echo "Current timezone: $(date)"
                            echo "TZ environment: $TZ"
                            
                            # Gradle 빌드 시 JVM 시간대 옵션 추가
                            SPRING_PROFILES_ACTIVE=${SPRING_PROFILES_ACTIVE} gradle clean build -x test \
                                -Duser.timezone=Asia/Seoul \
                                -Dorg.gradle.jvmargs="-Duser.timezone=Asia/Seoul"
                            
                            tar -czf app.tar.gz -C build/libs/ .
                        '''
                    } catch (Exception e) {
                        currentBuild.result = 'FAILURE'
                        error("Build failed: ${e.message}")
                    }
                }
            }
        }

        stage('Deploy to All Servers') {
            parallel {
                stage('Deploy to EC2 #1 (LB + Chat)') {
                    steps {
                        deployToServer(
                            env.CATXI_EC2_1_IP,
                            'EC2 #1 (Load Balancer + Chat Server)'
                        )
                    }
                }
                
                stage('Deploy to EC2 #2 (General API)') {
                    steps {
                        deployToServer(
                            env.CATXI_EC2_2_IP,
                            'EC2 #2 (General API Server)'
                        )
                    }
                }
                
                stage('Deploy to EC2 #3 (Chat)') {
                    steps {
                        deployToServer(
                            env.CATXI_EC2_3_IP,
                            'EC2 #3 (Chat Server)'
                        )
                    }
                }
            }
        }

        // 새로운 단계: Health Check
        stage('Health Check All Servers') {
            parallel {
                stage('Health Check EC2 #1') {
                    steps {
                        healthCheck(env.CATXI_EC2_1_IP, 'EC2 #1')
                    }
                }
                
                stage('Health Check EC2 #2') {
                    steps {
                        healthCheck(env.CATXI_EC2_2_IP, 'EC2 #2')
                    }
                }
                
                stage('Health Check EC2 #3') {
                    steps {
                        healthCheck(env.CATXI_EC2_3_IP, 'EC2 #3')
                    }
                }
            }
        }
    }

    post {
        success {
            discordSend(
                description: "멀티 서버 배포 성공! 🌈",
                footer: "EC2 #1 (${CATXI_EC2_1_IP}): LB+Chat ✅\nEC2 #2 (${CATXI_EC2_2_IP}): General API ✅\nEC2 #3 (${CATXI_EC2_3_IP}): Chat ✅\n모든 서버에 성공적으로 배포가 완료되었습니다.",
                link: env.BUILD_URL,
                result: currentBuild.currentResult,
                title: "${env.JOB_NAME} #${BUILD_NUMBER}",
                webhookURL: env.WEBHOOK_URL
            )
        }

        failure {
            discordSend(
                description: "멀티 서버 배포 또는 Health Check 실패. 로그를 확인해주세요! ⛈️",
                footer: "일부 또는 모든 서버 배포/Health Check에 실패했습니다. 상세 로그를 확인해주세요.",
                link: env.BUILD_URL,
                result: currentBuild.currentResult,
                title: "${env.JOB_NAME} #${BUILD_NUMBER}",
                webhookURL: env.WEBHOOK_URL
            )
        }
    }
}

// CPS 호환 배포 메서드 (시간대 설정 포함)
def deployToServer(String serverIP, String serverName) {
    script {
        try {
            echo "Deploying to ${serverName} - ${serverIP} with Asia/Seoul timezone"
            
            sshagent(['ec2_catxi']) {
                sh """
                    scp -o StrictHostKeyChecking=no -o ConnectTimeout=30 -P ${CATXI_PORT} app.tar.gz ${CATXI_USER}@${serverIP}:/home/${CATXI_USER}/app/
                    
                    # 서버에 시간대 설정과 함께 배포 스크립트 실행
                    ssh -o StrictHostKeyChecking=no -o ConnectTimeout=30 ${CATXI_USER}@${serverIP} -p ${CATXI_PORT} '
                        export TZ=Asia/Seoul
                        export JAVA_OPTS="-Duser.timezone=Asia/Seoul \$JAVA_OPTS"
                        echo "Server timezone: \$(date)"
                        bash /home/deploy/deploy.sh ${SPRING_PROFILES_ACTIVE}
                    '
                """
            }
            
            echo "Successfully deployed to ${serverName}"
            
        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            echo "Deployment failed to ${serverName}: ${e.message}"
            
            discordSend(
                description: "배포 실패: ${serverName}",
                footer: "서버 ${serverIP}에 배포 중 오류가 발생했습니다.\n오류: ${e.message}",
                link: env.BUILD_URL,
                result: 'FAILURE',
                title: "${env.JOB_NAME} #${BUILD_NUMBER}",
                webhookURL: env.WEBHOOK_URL
            )
            
            throw e
        }
    }
}

// Health Check 메서드
def healthCheck(String serverIP, String serverName) {
    script {
        try {
            echo "🔍 Health checking ${serverName} - ${serverIP}"
            
            sshagent(['ec2_catxi']) {
                timeout(time: 2, unit: 'MINUTES') {
                    waitUntil {
                        script {
                            try {
                                def result = sh(
                                    script: """
                                        ssh -o StrictHostKeyChecking=no ${CATXI_USER}@${serverIP} -p ${CATXI_PORT} '
                                            export TZ=Asia/Seoul
                                            echo "Health check time: \$(date)"
                                            curl -s -f http://localhost:8080/actuator/health | grep -q "UP"
                                        '
                                    """,
                                    returnStatus: true
                                )
                                
                                if (result == 0) {
                                    echo "✅ ${serverName} Health Check passed"
                                    return true
                                } else {
                                    echo "⏳ ${serverName} Health Check failed, retrying..."
                                    sleep(5)
                                    return false
                                }
                            } catch (Exception e) {
                                echo "⏳ ${serverName} Health Check exception, retrying..."
                                sleep(5)
                                return false
                            }
                        }
                    }
                }
            }
            
            echo "✅ ${serverName} Health Check completed successfully"
            
        } catch (Exception e) {
            currentBuild.result = 'FAILURE'
            echo "❌ Health Check failed for ${serverName}: ${e.message}"
            
            discordSend(
                description: "Health Check 실패: ${serverName}",
                footer: "서버 ${serverIP}의 Health Check에 실패했습니다.\n오류: ${e.message}",
                link: env.BUILD_URL,
                result: 'FAILURE',
                title: "${env.JOB_NAME} #${BUILD_NUMBER}",
                webhookURL: env.WEBHOOK_URL
            )
            
            throw e
        }
    }
}

