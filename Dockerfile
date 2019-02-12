FROM amazoncorretto:8
ENV JAVA_HOME=/usr/lib/jvm/java-1.8.0-amazon-corretto.x86_64
RUN yum install -y wget
RUN wget http://repos.fedorapeople.org/repos/dchen/apache-maven/epel-apache-maven.repo -O /etc/yum.repos.d/epel-apache-maven.repo
RUN sed -i s/\$releasever/6/g /etc/yum.repos.d/epel-apache-maven.repo
RUN yum install -y apache-maven && yum clean all
COPY src /build/src
COPY pom.xml /build
WORKDIR /build
RUN mvn clean package shade:shade
