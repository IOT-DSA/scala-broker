# Integrational test suit for scala-broker

to run all tests use in sbt console

```bash
project root
docker:publishLocal
project it
it-tests
```

to run specific test or test package

```bash
project root
docker:publishLocal
project it

# runs all it tests in package it
itTestOnly it.*

# runs all it tests in this specific test
itTestOnly it.ExampleSpec
```

