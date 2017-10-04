# Running Scala DSA Broker with Docker on a local VM

## Prerequisites

* Make sure Docker daemon is running.
* Run `setup` script to create _dsa_ network.

## Running DSA Broker in standalone mode

1. Run `start-broker` script.

2. Verify the docker container is running by issuing `docker ps` command. 
   You should see _dsa-broker_ container running.
   
3. Check broker logs by issuing `check-logs dsa-broker` command. This
   starts an Ubuntu container attached to the broker's exposed volumes. Once
   Ubuntu prompt is displayed, you can view the broker log in
   _/opt/docker/logs/application.log_ file. Exit the Ubuntu container when
   done with the logs by entering `exit` command at the prompt.
   
4. You can stop the broker by issuing `docker stop dsa-broker` command. To
   remove the stopped container, issue `docker rm dsa-broker` command.
   
## Running DSA Broker as N backends + M frontends

### Starting Broker backends

1. You can start any number of broker backends by repeatedly issuing command 
   `start-backend <name> <port>`, where `name` is the name that will be assigned to the 
   backend container as well as its virtual host registered at _dsa_ network; `port`
   is the local port bound to the broker's actor system.
   
   For example, to start three broker backends, you may type:
   
   `start-backend backend1 2551` 
   
   `start-backend backend2 2552`
   
   `start-backend backend3 2553`
   
2. After running `docker ps` you should see the running containers, one per backend
   with the names you specified. Note that by default the configuration files 
   _backend.conf_ and _frontend.conf_ under _conf_ directory specify the seed node as
   `backend1:2551`, therefore if you name your seed broker differently or use a
   different port, you will need to change those files accordingly.
   
3. To check the backend logs, you can issue `check-logs <name>` command, similar to one
   for the standalone mode, specifying the appropriate backend name.
   
4. To stop a particular backend process, issue `docker stop <name>` command; to remove 
   the container permanently issue `docker rm <name>` command.
   
### Starting Broker frontends

1. You can start any number of broker frontends by repeatedly issuing command 
   `start-frontend <name> <port>`, where `name` is the name that will be assigned to the 
   frontend container as well as its virtual host registered at _dsa_ network; `port`
   is the local port exposed for HTTP traffic.
   
   For example, to start a single broker frontend you may type:
   
   `start-frontend frontend 9000` 
   
2. After running `docker ps` you should see the running containers, one per frontend
   with the names you specified. Note that by default the configuration file 
   _frontend.conf_ under _conf_ directory specifies the backend seed node as
   `backend1:2551`, therefore if you named your seed broker backend differently or used a
   different port, you will need to change the configuration file accordingly.
   
3. To check the frontend logs, you can issue `check-logs <name>` command, similar to one
   for the standalone mode, specifying the appropriate frontend name.
   
4. To stop a particular frontend process, issue `docker stop <name>` command; to remove 
   the container permanently issue `docker rm <name>` command.
