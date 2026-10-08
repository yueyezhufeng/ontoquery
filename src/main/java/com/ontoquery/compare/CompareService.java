package com.ontoquery.compare;

import com.ontoquery.ontology.OntologyPipeline;
import com.ontoquery.support.PipelineResult;
import com.ontoquery.tradnl.TraditionalPipeline;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对比编排：同一问题并行送入两条管线，结果并排返回。
 * 线程池按《Java开发手册》手动创建并命名线程。
 *
 * @author 月夜烛峰
 */
@Service
public class CompareService {

    private static final Logger log = LoggerFactory.getLogger(CompareService.class);

    private static final int POOL_SIZE = 2;
    private static final long KEEP_ALIVE_SECONDS = 60L;

    private final OntologyPipeline ontologyPipeline;
    private final TraditionalPipeline traditionalPipeline;
    private final ExecutorService executor;

    public CompareService(OntologyPipeline ontologyPipeline, TraditionalPipeline traditionalPipeline) {
        this.ontologyPipeline = ontologyPipeline;
        this.traditionalPipeline = traditionalPipeline;
        this.executor = new ThreadPoolExecutor(POOL_SIZE, POOL_SIZE, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(16), new NamedThreadFactory());
    }

    /** 双管线并行执行 */
    public Map<String, Object> compare(String question) {
        Future<PipelineResult> ontologyFuture =
                executor.submit(() -> ontologyPipeline.answer(question));
        Future<PipelineResult> traditionalFuture =
                executor.submit(() -> traditionalPipeline.answer(question));
        PipelineResult ontology;
        PipelineResult traditional;
        try {
            ontology = ontologyFuture.get();
            traditional = traditionalFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("对比执行被中断", e);
        } catch (ExecutionException e) {
            log.error("对比执行异常", e.getCause());
            throw new IllegalStateException("对比执行异常: " + e.getCause().getMessage(), e.getCause());
        }
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("question", question);
        body.put("ontology", ontology);
        body.put("traditional", traditional);
        return body;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

    /** 命名线程工厂：compare-pipeline-1 / compare-pipeline-2 */
    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "compare-pipeline-" + index.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
