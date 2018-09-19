# how to update an environment

### Given
- Updating environment <env> 
- From branch <branch>
- Name of the image in AWS = broker-scala-performance
  
Do the following:  
  
1. Login to dev-proxy server or any other linux machine with kubectl configured  
2. Set kubectl to the appropriate cluster  
```
export KUBECONFIG=~/.kube/config-<env>  
```  
3. get latest code in the scala-broker dir (or other dir that has git repo=scala-broker)  
```
cd scala-broker/  
git checkout <branch>  
git pull
```  
4. build the project
```
sbt clean docker:publishLocal  
```  
5. tag the local image and upload to AWS registry  
```
docker tag iotdsa/broker-scala:0.4.0-SNAPSHOT 582161142462.dkr.ecr.us-west-2.amazonaws.com/broker-scala-performance  
`aws ecr get-login --no-include-email`  
docker push 582161142462.dkr.ecr.us-west-2.amazonaws.com/broker-scala-performance  
``` 
6. delete the pods (they will be recreated with latest image pulled)  
```
kubectl delete pods cloud-broker-cluster-0 cloud-broker-cluster-1 cloud-broker-cluster-2  
```


