# ci-bot

A bot that mirrors pull requests opened against one repository (so called "observed repository") to branches in
 another repository (so called "ci repository"), and report back the Checker status once the checks have completed.

```
Usage: java -jar ci-bot.jar [options]
  Options:
    --backlog, -b
      The number of hours the bot should go back in time when processing pull
      requests on startup.This should usually be inHours(currentTime -
      lastTimeBotShutdown).
      Default: 24
  * --ciRepository, -cr
      The repo to run the CI.
    --interval, -i
      The polling interval in seconds.
      Default: 300
  * --observedRepository, -or
      The repo to observe.
  * --token, -t
      The GitHub authorization token with write permissions for the CI
      repository.
  * --user, -u
      The GitHub account name to use for posting results.
```