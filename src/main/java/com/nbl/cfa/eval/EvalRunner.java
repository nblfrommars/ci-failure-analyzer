package com.nbl.cfa.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.nbl.cfa.classifier.ClassificationResult;
import com.nbl.cfa.classifier.FailureClassifier;
import com.nbl.cfa.worker.LogPreprocessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chương trình đánh giá: đọc labels.json + log trong thư mục eval-data, chạy đúng pipeline tiền xử lý + phân loại
 * như worker, rồi so với nhãn. Chỉ chạy khi cfa.eval.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "cfa.eval.enabled", havingValue = "true")
public class EvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LabelCase(String id,
                            @JsonProperty("log_file") String logFile,
                            @JsonProperty("error_type") String errorType,
                            @JsonProperty("is_flaky") boolean flaky,
                            @JsonProperty("final_job_status") String finalJobStatus) {
    }

    public record CaseReport(String id, String goldType, String predType, boolean typeOk,
                             boolean goldFlaky, boolean predFlaky, boolean flakyOk,
                             boolean evidenceInLog, String rootCause, String keyEvidence,
                             long millis, String error) {
    }

    public record Report(String model, int total, int evaluated, int errors,
                         double errorTypeAccuracy, double flakyAccuracy, double evidenceInLogRate,
                         Map<String, Integer> confusion, List<CaseReport> cases) {
    }

    private final String dataDir;
    private final FailureClassifier classifier;
    private final JsonMapper jsonMapper;

    public EvalRunner(@Value("${cfa.eval.data-dir:}") String dataDir,
                      FailureClassifier classifier,
                      JsonMapper jsonMapper) {
        this.dataDir = dataDir;
        this.classifier = classifier;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (dataDir.isBlank()) {
            log.error("Thiếu cfa.eval.data-dir (biến CFA_EVAL_DATA_DIR), không chạy đánh giá");
            return;
        }
        Path dir = Path.of(dataDir);
        LabelCase[] cases = jsonMapper.readValue(Files.readString(dir.resolve("labels.json")), LabelCase[].class);
        log.info("Bắt đầu đánh giá {} case với model {}", cases.length, classifier.modelName());

        List<CaseReport> reports = new ArrayList<>();
        Map<String, Integer> confusion = new TreeMap<>();
        int typeOk = 0;
        int flakyOk = 0;
        int evidenceOk = 0;
        int errors = 0;

        for (LabelCase c : cases) {
            long started = System.nanoTime();
            try {
                String raw = Files.readString(dir.resolve(c.logFile()));
                LogPreprocessor.Result pre = LogPreprocessor.process(raw);
                ClassificationResult r = classifier.classify(new FailureClassifier.Input(
                        pre.failedStep(), pre.excerpt(), pre.flaky(), c.finalJobStatus()));

                boolean tOk = r.errorType().value().equals(c.errorType());
                boolean fOk = pre.flaky() == c.flaky();
                long millis = (System.nanoTime() - started) / 1_000_000;

                typeOk += tOk ? 1 : 0;
                flakyOk += fOk ? 1 : 0;
                evidenceOk += r.evidenceInLog() ? 1 : 0;
                confusion.merge(c.errorType() + " -> " + r.errorType().value(), 1, Integer::sum);
                reports.add(new CaseReport(c.id(), c.errorType(), r.errorType().value(), tOk,
                        c.flaky(), pre.flaky(), fOk, r.evidenceInLog(),
                        r.rootCause(), r.keyEvidence(), millis, null));
                log.info("{}: gold={} pred={} typeOk={} flakyOk={} evidenceInLog={} ({} ms)",
                        c.id(), c.errorType(), r.errorType().value(), tOk, fOk, r.evidenceInLog(), millis);
            } catch (Exception e) {
                errors++;
                reports.add(new CaseReport(c.id(), c.errorType(), null, false, c.flaky(), false, false,
                        false, null, null, (System.nanoTime() - started) / 1_000_000, e.toString()));
                log.error("{}: lỗi khi đánh giá", c.id(), e);
            }
        }

        int evaluated = cases.length - errors;
        Report report = new Report(classifier.modelName(), cases.length, evaluated, errors,
                ratio(typeOk, evaluated), ratio(flakyOk, evaluated), ratio(evidenceOk, evaluated),
                confusion, reports);

        Path outDir = dir.resolve("reports");
        Files.createDirectories(outDir);
        Path out = outDir.resolve("eval-" + classifier.modelName().replaceAll("[^A-Za-z0-9.-]", "_") + ".json");
        Files.writeString(out, jsonMapper.writeValueAsString(report));

        log.info("=== KẾT QUẢ ({}): error_type {}/{} đúng, flaky {}/{} đúng, evidence có trong log {}/{}, lỗi {} ===",
                classifier.modelName(), typeOk, evaluated, flakyOk, evaluated, evidenceOk, evaluated, errors);
        log.info("Báo cáo chi tiết: {}", out);
    }

    private static double ratio(int part, int total) {
        return total == 0 ? 0.0 : (double) part / total;
    }
}