#!/bin/bash

kubectl delete deployment camel-health -n camel-k
mvn clean package -DskipTests
docker build -t chedi1/camel:1.0 .
minikube ssh "docker rmi -f chedi1/camel:1.0"
minikube image load chedi1/camel:1.0
kubectl apply -f deployment.yaml
