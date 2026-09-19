# FluidTokens Aquarium Node 

Welcome to the FluidTokens Aquarium Node Repo

The Aquarium Node is a java app which indexes FluidTokens users' _Tanks_ utxos and processes _Scheduled Transactions_
when conditions are met.

Node operators will be required to stake a certain amount of FLDT tokens in order to be allowed to process _Scheduled Transactions_.

Operators will periodically receive compensation for the work executed.

## What you need

- **30,000 $FLDT**, delegated to the node (held in a separate wallet — see the deployment guide)
- **Docker** with the Compose plugin
- A **Cardano relay** to connect to, and a **Blockfrost** project key
- A **dedicated wallet mnemonic**, used for nothing else
- RAM and disk: the shipped Compose file caps the node at **4 GB**. Disk is dominated by Postgres
  and depends on how far back you sync — watch it during the first sync rather than trusting a
  figure

## Documentation

| | |
|---|---|
| **[docs/deploying.md](docs/deploying.md)** | Standing up a node for the first time — keys, configuration, first start, reaching the UI safely, arming the bot, the market specification |
| **[docs/upgrading.md](docs/upgrading.md)** | Already running one — version bumps, and the re-index/wipe sequence a contract redeploy requires |

Both cover **Docker Compose**. Systemd, Kubernetes and Nomad all work; the ordering, secrets and
exposure guidance applies unchanged.

## Quick start (mainnet)

```bash
git clone https://github.com/FluidTokens/ft-aquarium-node.git
cd ft-aquarium-node/docker
cp .env.example .env
chmod 600 .env                  # it will hold a seed phrase
# set BLOCKFROST_KEY, WALLET_MNEMONIC, DB_USERNAME, DB_PASSWORD,
#     STORE_CARDANO_HOST, STORE_CARDANO_PORT and the image name/version
docker compose up -d
docker compose logs -f aquarium
```

Then get 30k FLDT (for example on [Minswap](https://minswap.org/tokens/fldt)) into a **separate**
wallet — it can be cold — and delegate it to your node's address
[here](https://aquarium-qa.fluidtokens.com/validator). The node's own hot wallet only needs about
10 ADA to operate.

The first sync takes a while; after that it tracks the tip. Every month 50% of generated fees are
split across the nodes that performed transactions.

**⛔ The node ships with everything OFF** — the scheduled-transaction processor, the liquidation bot
and compound all have to be turned on deliberately. Read
[docs/deploying.md](docs/deploying.md) §8 before arming anything, and rehearse in `shadow` first.

**⛔ Do not publish port 8080.** The operator UI has no authentication in front of it. Reach it
through an SSH tunnel or a private network — [docs/deploying.md](docs/deploying.md) §7.

## How it works

The Aquarium Node requires two additional components to work:

1. A Cardano Node (which can either be local or remote)
2. A local Postgres Database

The Aquarium Node leverages [BloxBean Yaci Store](https://github.com/bloxbean/yaci-store) to index the Cardano blockchain and 
saves to a local database relevant data such as:

* the UTxOs of Aquarium Scheduled transactions
* Aquarium Parameters UTxO 
* Aquarium Staker UTxOs

Periodically, the node loads all the UTxOs of the `Scheduled Transaction` contract, deserialise the attached data (if any),
checks if any of the _Scheduled Transaction_ can be executed and eventually prepares, signs and submits the transaction to a Node via [Blockfrost](https://blockfrost.io/).

Here below a high level design of the Acquarium Node:

![Aquarium Node High Level Design](AQUARIUM_DESIGN.jpg)

### Alternative solutions

When designing the Aquarium node, alternative solutions were considered and after careful considerations it was agreed to proceed with using 
Java and Yaci Store.

The most common approach adopted in the Cardano ecosystem is to used Kupo and Ogmios as services to scan the blockchain and query utxos.
Although these two services offer all the apis required to the Aquarium Node, it also means an operator requires to locally run a Cardano Node
along Kupo and Ogmios, significantly increasing complexity and costs of running an Aquarium Node.

By leveraging Yaci, while some additional configuration is required, the Aquarium Node is able to both traverse the chain and locally store relevant utxo.

Using Yaci Store also gives the following benefits:
1. Simple, concise and fast code to access data via SQL queries
2. Straightforward horizontal scaling: by replicating the DB and launching Yaci in read only mode, is very simple to linearly scale
the Aquarium Node
3. Blockfrost api: Yaci can serve blockfrost compatible APIs out of the box

## Building it yourself

You do not need to: CI publishes an image built from `main` **after** running the full test suite.
If you would rather verify what you run, you need JDK 21:

```bash
./gradlew bootJar               # build/libs/ft-aquarium-node-<version>.jar
docker build -t ft-aquarium-node:local .
```

The image records the commit it was built from and reports it on `/healthcheck` — including whether
the tree was dirty, and `unknown` when it could not tell. See
[docs/deploying.md](docs/deploying.md) §11.

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
