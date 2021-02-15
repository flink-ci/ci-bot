# ci-bot

A bot that mirrors pull requests opened against one repository (so called "observed repository") to branches in
 another repository (so called "ci repository"), and report back the Checker status once the checks have completed.

```
Usage: java -jar ci-bot.jar [options]
  Options:
  * --azureToken, -at
      The Azure authorization token with cancel permissions for the CI
      repository.
    --backlog, -b
      The number of hours the bot should go back in time when processing pull
      requests on startup.This should usually be inHours(currentTime -
      lastTimeBotShutdown).
      Default: 24
    --checkerNamePattern, -cnp
      A regex to select github checker runs to process.
      Default: .*
  * --ciRepository, -cr
      The repo to run the CI.
  * --githubToken, -gt
      The GitHub authorization token with write permissions for the CI
      repository.
    --interval, -i
      The polling interval in seconds.
      Default: 300
  * --observedRepository, -or
      The repo to observe.
  * --user, -u
      The GitHub account name to use for posting results.
```