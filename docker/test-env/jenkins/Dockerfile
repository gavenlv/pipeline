FROM jenkins/jenkins:2.504.1-lts

# Pre-install all required plugins at image build time
COPY plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt

USER root
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl wget ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

USER jenkins
