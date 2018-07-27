how kubernetes is setup

  107  az login

  109  az group create --name acuitysso --location eastus

  112  az acr create --resource-group acuitysso --name acuityregistry --sku Basic
  113  az acr login --name acuityregistry

    116  az acr list --resource-group acuitysso --query "[].{acrLoginServer:loginServer}" --output table
  117  docker tag iotdsa/broker-scala acuityregistry.azurecr.io/broker-scala:latest
  118  docker tag iotdsa/broker-scala:0.4.0-SNAPSHOT acuityregistry.azurecr.io/broker-scala:latest
  119  docker tag graphana acuityregistry.azurecr.io/graphana:latest
  120  docker tag openzipkin/zipkin acuityregistry.azurecr.io/zipkin:latest
  121  docker build -t acuityregistry.azurecr.io/shell-dslink:latest docker/docker-compose/shell-dslink/
  122  docker build -t acuityregistry.azurecr.io/weather-dslink:latest docker/docker-compose/weather-dslink/
  123  docker images | grep azure
  124  docker build -t acuityregistry.azurecr.io/system-dslink:latest docker/docker-compose/system/
  125  docker images | grep azure
  126  docker push acuityregistry.azurecr.io/system-dslink
  127  docker push acuityregistry.azurecr.io/weather-dslink
  128  docker push acuityregistry.azurecr.io/shell-dslink
  129  docker push acuityregistry.azurecr.io/broker-scala
  130  docker push acuityregistry.azurecr.io/zipkin
  131  docker push acuityregistry.azurecr.io/graphana



   133  az acr repository list --name acuityregistry --output table
  134  az provider register -n Microsoft.ContainerService
  135  az provider show -n Microsoft.ContainerService
  136  az aks create --resource-group acuitysso --name acuitykubernetes --node-count 1 --generate-ssh-keys
  137  az provider register -n Microsoft.Compute
  138  az provider register -n Microsoft.Storage
  139  az provider register -n Microsoft.Network
  140  az aks create --resource-group acuitysso --name acuitykubernetes --node-count 1 --generate-ssh-keys
  141  az aks install-cli
  142  az aks get-credentials --resource-group acuitysso --name acuitykubernetes




  CLIENT_ID=$(az aks show --resource-group acuitysso --name acuitykubernetes --query "servicePrincipalProfile.clientId" --output tsv)

  ACR_ID=$(az acr show --name acuityregistry --resource-group acuitysso --query "id" --output tsv)

  az role assignment create --assignee $CLIENT_ID --role Reader --scope $ACR_ID
