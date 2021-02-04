# datahike-bookeeper

[Datahike](https://github.com/replikativ/datahike) with [Pulsar](https://github.com/apache/pulsar) as the transaction engine.

## Usage

After including the datahike API and the datahike-pulsar namespace, you can use this transactor backend using the keyword `:pulsar`

```clj
(d/connect 
  {:transactor
    {:backend :pulsar ; Required
    :subscription-id "datahike-instance-1" ; Required. Use a unique subscription id for each datahike instance.
    :consumer-poll-timeout-ms 1000
    :transaction-rtt-timeout-ms 10000
    :pulsar ; Required
    {:url "pulsar://localhost:6650" ; Required
    :topic "my-datahike-topic" ; Required
    :token "my-jwt-authentication-token"
    }}})
```

## License

Copyright © 2021 Arctype Corporation.

This program and the accompanying materials are made available under the terms of the Eclipse Public License 1.0.
