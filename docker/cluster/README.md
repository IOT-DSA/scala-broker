# Running Scala DSA Broker with Docker on multiple VMs

## Prerequisites

* Make sure Docker daemon is running on each VM.

## Starting Broker nodes

1. Copy the contents of _cluster_ directory to each VM where a broker process is to be run.

2. Run `setup` script on each VM to create _dsa_ network.

3. Edit _application.conf_, _backend.conf_ and _frontend.conf_ configuration files
   under _conf_ folder to set the correct IPs of the host VM and seed node.

4. Start backend processes by issuing `start-backend` command on each VM where a 
   broker backend instance needs to run. You can verify the backend process is running
   by issuing `docker ps` command and seeing the **dsa-backend** container.

5. Start frontend processes by issuing `start-frontend` command on each VM where a 
   broker frontend instance needs to run. You can verify the frontend process is running
   by issuing `docker ps` command and seeing the **dsa-frontend** container.
   
6. To check the broker backend/frontend logs, you can either issue 
   `docker logs dsa-backend/dsa-frontend` command on the appropriate VM, or attach an 
   Ubuntu container to a exposed volume with `check-logs dsa-backend/dsa-frontend` 
   command.
   
7. To stop a particular process, issue `docker stop dsa-backend/dsa-frontend` command; 
   to remove the container permanently issue `docker rm dsa-backend/dsa-frontend` command.