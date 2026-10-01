package backend.academy.linktracker.scrapper.scheduler;

import backend.academy.linktracker.scrapper.properties.SchedulerProperties;
import backend.academy.linktracker.scrapper.service.LinkMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true")
public class LinkUpdaterScheduler {
    private static final Logger log = LoggerFactory.getLogger(LinkUpdaterScheduler.class);
    private final LinkMonitorService monitor;
    private final SchedulerProperties properties;
    private volatile boolean stopping;

    public LinkUpdaterScheduler(LinkMonitorService monitor, SchedulerProperties properties) {
        this.monitor = monitor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.interval}", initialDelayString = "${app.scheduler.interval}")
    public void update() {
        try {
            for (int i = 0; i < properties.batchSize() && !stopping; i++) {
                // Every call has its own transaction. Other instances skip locked/delayed rows.
                if (!monitor.checkNext()) break;
            }
        } catch (RuntimeException exception) {
            log.error("Monitoring batch failed; database state will be retried", exception);
        }
    }

    @EventListener(ContextClosedEvent.class)
    public void stop() {
        stopping = true;
    }
}
