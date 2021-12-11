Docker setup
====

This folder container all the necessary material to build and run Alephium via docker container.

# Prerequisites

We're using [docker-compose](https://docs.docker.com/compose/) to run Alephium here.
Make sure you installed `docker` and `docker-compose` before proceeding further.

If you prefer running `docker` or `docker-compose` command without `sudo`, add your use name
in the `docker` group by running the following command.

```shell
sudo usermod -aG docker $USER
```

# Run

copy env-example to .env and change your credentials with a strong password
```shell
cp env-example .env
```


The provided [docker-compose.yml](./docker-compose.yml) file will be used to run Alephium:

```shell
docker-compose stop && docker-compose rm -f alephium && docker-compose pull && docker-compose up -d
```

The default config connects your container to the mainnet, and makes the API available to [http://127.0.0.1:12973/docs](http://127.0.0.1:12973/docs):

```
curl http://127.0.0.1:12973/infos/self-clique
```

## Using Public Swagger Endpoint
- the Secured Endpoint link is : https://Public_IP:8030/docs
- Ignore the Certificate Message and use your credentials to login
- change Server variables : 
  - protocol : https
  - host : your_public_ip
  - port : 8030

use https://login:password@public_IP:8030 on sweep-all.sh to access the swagger api endpoint using your credentials

## Monitoring

A local instance of grafana will be started at `http://127.0.0.1:3000` with two built-in dashboards:

`JVM Overview`:

```
http://127.0.0.1:3000/d/ME6diT3Mk/jvm-overview?orgId=1&refresh=30s
```

and `Alephium Overview`:
```
http://127.0.0.1:3000/d/S3eJTo3Mk/alephium-overview?orgId=1&refresh=30s
```

# Configuration

In order to persist your data (blocks, wallets, ...), two volumes/mounts can be used.

- `/alephium-home/.alephium` inside the container is where the chain's data and logs are stored
- `/alephium-home/.alephium-wallets` inside the container is where the wallets are stored.

Create these folders on the host:

```shell
mkdir ./alephium-data ./alephium-wallets && chown nobody ./alephium-data ./alephium-wallets
```

Mount them as volumes inside the container:

```
    volumes:
      - ./alephium-data:/alephium-home/.alephium
      - ./alephium-wallets:/alephium-home/.alephium-wallets
```

All good, your data will survive accross restarts!

For more configuration, check the [Mainnet Guide](https://wiki.alephium.org/Mainnet-Guide.html) on the wiki.

## GPU Miner (Optional)

Make sure that the Nvidia graphics card works on the host machine. One way to verify is to run
the `nvidia-smi` command.

Install [nvidia-docker](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html#docker),
which enables the docker runtime to access the Nvidia graphics card on the host machine.

Restart docker daemon and run
```shell
docker run --rm --gpus all --privileged --entrypoint nvidia-smi alephium/gpu-miner:latest
```
to verify the setup is successful. It should have the same output as running `nvidia-smi` on the host machine.

To start the GPU miner docker container, either run the following `docker-compose` command (requires version [v1.28.0+](https://docs.docker.com/compose/gpu-support/#enabling-gpu-access-to-service-containers))

```shell
docker-compose -f docker-compose.yml -f docker-compose.gpu-miner.yml stop && \
  docker-compose -f docker-compose.yml -f docker-compose.gpu-miner.yml rm -f alephium && \
  docker-compose -f docker-compose.yml -f docker-compose.gpu-miner.yml pull && \
  docker-compose -f docker-compose.yml -f docker-compose.gpu-miner.yml up -d
```

or run the following docker command:
```shell
docker run --network="docker_default" --gpus all --privileged --name gpu-miner -d alephium/gpu-miner:latest alephium
```
