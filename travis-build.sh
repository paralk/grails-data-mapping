#!/bin/bash
EXIT_STATUS=0

case "$GORM_IMPL"  in
    hibernate)
        ./gradlew grails-datastore-gorm-hibernate:test || EXIT_STATUS=$?
        ;;
    hibernate4)
        ./gradlew grails-datastore-gorm-hibernate4:test --stacktrace || EXIT_STATUS=$?
        ;;
    mongodb)
        ./gradlew grails-datastore-gorm-mongodb:test --stacktrace || EXIT_STATUS=$?
        ;;
    redis)
        ./gradlew grails-datastore-gorm-redis:test || EXIT_STATUS=$?
        ;;
    cassandra)
        # wait for Cassandra to start up
        sleep 5
        ./gradlew grails-datastore-gorm-cassandra:test || EXIT_STATUS=$?
        ;;
    neo4j)
        ./gradlew grails-datastore-gorm-neo4j:test || EXIT_STATUS=$?
        ;;
    restclient)
        ./gradlew grails-datastore-gorm-rest-client:test || EXIT_STATUS=$?
        ;;
    *)
        ./gradlew testClasses || EXIT_STATUS=$?
        ./gradlew grails-datastore-gorm-test:test || EXIT_STATUS=$?
        ;;
esac

./travis-publish.sh || EXIT_STATUS=$?

exit $EXIT_STATUS



