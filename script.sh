#!/bin/bash

kubectl delete deployment camel-health -n camel-k
mvn clean package -DskipTests
docker build -t chedi1/camel:1.0 .
minikube ssh "docker rmi -f chedi1/camel:1.0"
minikube image load chedi1/camel:1.0
kubectl create configmap camel-route-config-PROJECT_NAME --from-file=application.properties=PROJECT_NAME-k8s.properties -n msic-app --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f deployment.yaml
