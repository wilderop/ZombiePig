# ZombiePig

Survival Paper plugin that PMs new players (under 10 hours) in the combined voice of AZPBMD public chat.

It is clearly a **server bot**, still `/msg`-able. Unsolicited PMs on join, spawn deaths, and standing around spawn with nothing; also replies privately if they ask “what do I do” in public chat. `/zombiepig off` mutes it.

Grok replies go through a localhost sidecar that spends **grok-build weekly credits** (wilder0p grok.com OAuth). When remaining weekly credits are under 25%, it switches to canned lines.

## Layout

- Plugin: this Maven project → `target/ZombiePig.jar`
- Sidecar: `sidecar/zombiepigd.py` on `127.0.0.1:18787`
- Unit: `sidecar/azpbmd-zombiepigd.service`

## Deploy

```bash
mvn -q -f /mnt/pool/projects/ZombiePig/pom.xml clean package
install-plugin-jar /mnt/pool/projects/ZombiePig/target/ZombiePig.jar /mnt/pool/survival/plugins/ZombiePig.jar
sudo cp /mnt/pool/projects/ZombiePig/sidecar/azpbmd-zombiepigd.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now azpbmd-zombiepigd.service
```

The jar loads on the next survival JVM start. Do not `cp` over a live `plugins/*.jar`.

## Commands

| Command | Who |
|---|---|
| `/zombiepig off` / `on` | anyone |
| `/zombiepig status` | anyone (credits line is from the sidecar) |
| `/zombiepig reload` | op |
| `/zombiepig test [player]` | op, forces a PM |
| `/msg ZombiePig …` / `/r` | two-way while under 10 hours |
