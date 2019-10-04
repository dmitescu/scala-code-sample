# Description

This is a dummy event processing service, meant to receive events. For the behaviour,
please check out the processor tests, and for the protocol, the encoder/decoder tests.

You can tune the application with the following environmental variables:

`V_SOURCE_PORT` - default 9090
`V_CLIENTS`_PORT - default 9099
`V_ADDR` - default 0.0.0.0 (preferably 127.0.0.1)
`V_MAX_CLIENTS` - 100 (works better with less)
`V_BATCH_INTERVAL` - 100 (milliseconds to wait before checking the cache for events)
`V_CACHE_SIZE` - 500 (amount of events that are to be processed)
`V_CLEANUP_FACTOR` - default 5 - take the probability of Random.nextBool,
	multiply it by 5, and that is how often the cache is checked for
	overhead

# Protocol

Each event is delimited by CRLF and begins with an event ID and a procedure,
which are describe below:

- user 1 follows user 2 (user 2 gets notified): ```1 F 1 2```
- user 2 unfollows user 1: ```2 U 2 1```
- user 1 broadcasts (everyone is notified): ```3 B 1```
- user 1 messages 2 (2 gets notified): ```4 P 1 2```
- user 1 updates status (followers are notified) ```5 S 1```

# Synopsis

```
docker build test/ -t styx-test-clients
java -jar styx.jar &
docker run --network=host --rm styx-test-clients
```

# TODOs

The code could use many improvements, but in my opinion it's
written in such a way that it would easily allow for such improvements and
future changes.

Here is a list of todos:

- tests for reading/writing streams
- tests for the event consumer (should be trivial)
- unit tests for the event processor (might require some refactoring)
- fixing the task rejection exception caused by using `cats-effect`
- solving the boilerplate code problem of having to try out by pattern matching,
  the case classes; see the comments in `EventTypeDTO.scala`
- refactor the code to easily deploy child nodes or so
- moving the object classes into case classes; defining traits and instances
  for refactoring later (i.e: the cache insertion operation can be defined
  as an Applicative instance with a certain subtype - and so all the main other
  functions which return effects; in turn, instatiating implicit instances
  of said functions would be as easy as importing the package object).
