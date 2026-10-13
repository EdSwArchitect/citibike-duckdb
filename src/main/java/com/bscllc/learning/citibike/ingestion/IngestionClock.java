package com.bscllc.learning.citibike.ingestion;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.time.Clock;
@jakarta.enterprise.context.ApplicationScoped
public class IngestionClock {
    @Produces @Singleton public Clock clock(){return Clock.systemUTC();}
}
