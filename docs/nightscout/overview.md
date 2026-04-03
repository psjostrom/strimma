# What is Nightscout?

[Nightscout](http://www.nightscout.info/) (also known as "CGM in the Cloud") is an open-source, community-built platform for storing and viewing CGM data. It's been the backbone of the DIY diabetes community since 2014.

---

## Why Nightscout Matters

Nightscout gives you:

- **Cloud storage** — your glucose history in one place, accessible from anywhere
- **Web dashboard** — view your glucose on any browser (phone, tablet, computer)
- **Remote monitoring** — caregivers can see your glucose in real-time
- **Data portability** — your data, your server, your rules
- **Ecosystem integration** — hundreds of apps and devices work with Nightscout

---

## How Strimma Uses Nightscout

Strimma is a **Nightscout-compatible client**. It can:

1. **Push readings** — upload glucose data to your Nightscout server in real-time
2. **Pull history** — backfill readings from Nightscout into Strimma's local database
3. **Follow remotely** — poll a Nightscout server for readings (follower mode)
4. **Fetch treatments** — read bolus, carb, and basal data from Nightscout for IOB display

Strimma fully complies with the Nightscout API specification. It works with **any Nightscout server** — the standard Nightscout project, Nightscout forks, or custom implementations like [Springa](https://github.com/psjostrom/springa).

---

## Setting Up a Nightscout Server

If you don't have a Nightscout server yet, the community maintains guides for several hosting options:

- [Nightscout on Fly.io](https://nightscout.github.io/vendors/fly.io/new_user/) — free tier available
- [Nightscout on Railway](https://nightscout.github.io/vendors/railway/new_user/)
- [Nightscout on Heroku](https://nightscout.github.io/vendors/heroku/new_user/)
- [ns.10be.de](https://ns.10be.de/) — managed Nightscout hosting

Full setup documentation: [nightscout.github.io](https://nightscout.github.io/)

---

## Next Steps

- [Push Setup](push-setup.md) — configure Strimma to upload readings
- [Follower Mode](follower-setup.md) — follow a remote Nightscout server
