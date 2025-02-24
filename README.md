# FluidTokens Aquarium Node

Welcome to the FluidTokens Aquarium Node Repo

The Aquarium Node is a java app which indexes FluidTokens users' _Tanks_ utxos and processes _Scheduled Transactions_
when conditions are met.

Node operators will be required to stake a certain amount of FLDT tokens in order to be allowed to process _Scheduled Transactions_.

Operators will periodically receive compensation for the work executed.

## How does it work

As mentioned, the Aquarium Node is a Java App, which leverages BloxBean Yaci Store indexer to find and persist on a local
Postgres Database, relevant utxos.

Periodically, the node checks if any of the _Scheduled Transaction_ can be executed by querying the local database.

_Scheduled Transactions_ which are ready to be processed are filtered out, assembled ans submitted to the Cardano network.

In order to validate and submit the transaction, a valid BlockFrost API Key is required.

## How to build

The Aquarium node is a Java Springboot App, and you can build a standalone, self contained fat jar by executing in the root 
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

For preview you will have to replace all network related properties and adjust accordingly

### Customise your `.env` file for mainnet

Copy the `.env.example` into `.env` file.

Set the following two env properties:

```bash
## Blockfrost Key
BLOCKFROST_KEY=

## Wallet Seed (mnemonic)
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
> When launching for the first time the node, 

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
