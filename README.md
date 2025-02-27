# FluidTokens Aquarium Node 

Welcome to the FluidTokens Aquarium Node Repo

The Aquarium Node is a java app which indexes FluidTokens users' _Tanks_ utxos and processes _Scheduled Transactions_
when conditions are met.

Node operators will be required to stake a certain amount of FLDT tokens in order to be allowed to process _Scheduled Transactions_.

Operators will periodically receive compensation for the work executed.

## TLDR

Steps to run the aquarium validator are simple:
1. rename the file docker/.env.example in .env
2. set blockfrost APIKEY and the mnemonic phrase of a dedicated wallet containing only the 30k FLDT and 10 ADA inside the .env file
3. run `docker compose build` inside the docker folder
4. run ` docker compose up` inside the docker folder
5. That's it! First time will take a bit to sync with the genesis of Aquarium tx but then will be super fast indexer 


## How does it work

As mentioned, the Aquarium Node is a Java App, which leverages BloxBean Yaci Store indexer to find and persist on a local
Postgres Database, relevant utxos.

Periodically, the node checks if any of the _Scheduled Transaction_ can be executed by querying the local database.

_Scheduled Transactions_ which are ready to be processed are filtered out, assembled and submitted to the Cardano network.

In order to validate and submit the transaction, a valid BlockFrost API Key is required.

## How to build

The Aquarium node is a Java Spring Boot App, and you can build a standalone, self-contained _fat jar_ by executing in the root 
of the project the command 

```bash
./gradlew bootJar
```

The generated `jar` will be found in `build/libs/ft-aquarium-node-<version>.jar`.

You can then either use this library directly on your host system via 

```bash
java -jar build/libs/ft-aquarium-node-<version>.jar 
``` 

Or build a docker image with it.

The FluidTokens team has conveniently built a docker images for you already, but to maximise safety, we do recommend 
to build and run your own.

## How to run

This guide will only illustrate a basic, non-production ready, setup to run the Aquarium Node.

In the `docker` folder you will find an example `docker-compose.yaml` which you can use to run the node on both supported 
network: `mainnet` and `preview`.

In order to configure your node, you can leverage `.env` files to customise the way the node is run.

An `mainnet` compatible, example `.env.example` has been provided with pre-configured value for the non-sensitive properties.

For the preview network you will have to replace all network related properties and adjust accordingly

### Customise your `.env` file for mainnet

Copy the `.env.example` into `.env` file.

Set the following two env properties:

```bash
## Blockfrost Key, the free account plan is ok
BLOCKFROST_KEY=

## Wallet Seed (mnemonic), this wallet should contain 30k FLDT and 10 ADA to start, create a dedicated wallet and never share the mnemonic with anyone
WALLET_MNEMONIC=lorem ipsum
```

Ensure that the wallet has already staked the required FLDT tokens, or alternatively the wallet contains enough FLDT tokens
and the additional following property:

```
## Aquarium configuration
# Whether the bot should attempt to stake tokens if required.
AQUARIUM_STAKING_AUTO=false
```

is set to true.

> [!NOTE]  
> When launching for the very first time, the node will need to scan Cardano history from [Aquarium genesis block](https://cexplorer.io/block/8d55468951b4a8ee9f074e21fbe52574155665585887d002fce00b06a13d3de1)
> which was minted on the 12h of February 2025. Although the process is extremely fast, depending on your hardware, 
> on where your Cardano node is (local in your network, or remote) it might take a few hours for the node to index all required utxos.
> During sync, if you have enabled automatic staking, you might see errors popping from time to time as the node might not be able to 
> execute auto-staking or even trying to process transaction which were scheduled in the past and have been already processed since.
> Should this be the case, once indexing is complete (see below to understand how to detect this), you will need to restart your node,
> which will promptly check for your staking, and if missing, it will try to execute it.

### How to understand if my node is synced?

Very simple, issue a `docker logs aquarium --tail 50`

If you see something that resembles this line:
```bash
2025-02-24T11:05:00.500Z INFO 1 --- [ntLoopGroup-4-1] c.b.c.y.s.c.service.CursorServiceImpl : # of blocks written: 1
2025-02-24T11:05:00.500Z INFO 1 --- [ntLoopGroup-4-1] c.b.c.y.s.c.service.CursorServiceImpl : Block No: 11525533
```

if means your node is up to tip and is processing 1 block at the time (i.e. the latest block).

## Development Notes

### How to Setup local Postgres for dev

Init local dev psql db

`createuser --superuser postgres`

`psql -U postgres`

Then create db:

```
CREATE USER fluidtokens PASSWORD 'password';

CREATE DATABASE aquarium WITH OWNER fluidtokens;
```
