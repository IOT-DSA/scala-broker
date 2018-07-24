# scala-broker
Scala-based implementation of DSA broker

## Deployment to local machine

#### Prerequisites:

- Ubuntu 16.04
- No containers running (ansible includes docker prune commands)
- sudoer

#### 1. install ansible with required modules

```
sudo apt-get install -y software-properties-common &&
sudo apt-add-repository ppa:ansible/ansible &&
sudo apt-get update &&
sudo apt-get install -y ansible python-pip
sudo pip install openshift --ignore-installed

// below needs to go to ansible
sudo echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list &&
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823 &&
sudo apt-get update &&
sudo apt-get install sbt &&
sudo apt-get purge openjdk-\* &&
sudo add-apt-repository ppa:webupd8team/java &&
sudo apt-get update &&
sudo apt-get install oracle-java8-installer
```

#### 2. Install minikube locally

```
git clone https://github.com/IOT-DSA/scala-broker.git
cd scala-broker/ansible
sudo ansible-playbook -i inventory -c local full.yml
```

### 3. Build broker with sbt and publish docker image
```
sbt clean docker:publishLocal
docker build -t graphana:latest docker/docker-compose/graphana/
minikube start --vm-driver=none
```

#### 4. Deploy cloud broker pod to Kubernetes with

```
kubectl create -f ./docker/kubernetes-deployments/all.deployment --validate=false
kubectl create -f ./docker/kubernetes-deployments/metrics.deployment --validate=false
```