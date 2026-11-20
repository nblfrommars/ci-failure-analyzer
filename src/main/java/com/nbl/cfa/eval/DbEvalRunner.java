package com.nbl.cfa.eval;

import com.nbl.cfa.classifier.ClassificationResult;
import com.nbl.cfa.classifier.ErrorType;
import com.nbl.cfa.classifier.FailureClassifier;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nhãn chuẩn (gold) suy ra từ TÊN NHÁNH của PR, vì lỗi được cố ý tạo ra theo từng loại:
 *   fail/test-*, fail/flaky-*, fail/dep-*, fail/cfg-*, fail/build-*, fail/lint-*, fail/deploy-*, fail/other-*
 * Với mỗi nhánh lấy lần chạy mới nhất, chạy lại bộ phân loại trên log đã lưu (đúng dữ liệu worker đã dùng),
 * so với nhãn chuẩn. Chỉ chạy khi cfa.eval.db-enabled=true. Có thể đổi model để so sánh.
 */
@Component
@ConditionalOnProperty(name = "cfa.eval.db-enabled", havingValue = "true")
public class DbEvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbEvalRunner.class);

    private record Row(String branch, String conclusion, String failedStep, String excerpt, boolean flaky) {
    }

    private record Gold(ErrorType type, boolean flaky) {
    }

    private final JdbcClient jdbc;
    private final FailureClassifier classifier;
    private final JsonMapper jsonMapper;
    private final String reportDir;

    public DbEvalRunner(JdbcClient jdbc, FailureClassifier classifier, JsonMapper jsonMapper,
                        @Value("${cfa.eval.report-dir:eval-reports}") String reportDir) {
        this.jdbc = jdbc;
        this.classifier = classifier;
        this.jsonMapper = jsonMapper;
        this.reportDir = reportDir;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<Row> rows = jdbc.sql("""
                        SELECT DISTINCT ON (r.head_branch)
                               r.head_branch, r.conclusion, j.failed_step, j.cleaned_log, j.is_flaky
                          FROM job_failure j
                          JOIN pipeline_run r ON r.id = j.pipeline_run_id
                         WHERE r.head_branch LIKE 'fail/%'
                         ORDER BY r.head_branch, r.id DESC, j.id DESC
                        """)
                .query((rs, n) -> new Row(rs.getString("head_branch"), rs.getString("conclusion"),
                        rs.getString("failed_step"), rs.getString("cleaned_log"), rs.getBoolean("is_flaky")))
                .list();
        log.info("Đánh giá từ DB: {} nhánh fail/*, model {}", rows.size(), classifier.modelName());

        List<EvalRunner.CaseReport> reports = new ArrayList<>();
        Map<String, Integer> confusion = new TreeMap<>();
        Map<ErrorType, int[]> perClass = new TreeMap<>(); // [đúng, tổng]
        int typeOk = 0;
        int flakyOk = 0;
        int evidenceOk = 0;
        int errors = 0;
        int skipped = 0;

        for (Row row : rows) {
            Gold gold = goldFromBranch(row.branch());
            if (gold == null || row.excerpt() == null) {
                skipped++;
                log.warn("Bỏ qua nhánh {} (tên không theo quy ước hoặc không có log)", row.branch());
                continue;
            }
            long started = System.nanoTime();
            try {
                String status = "success".equals(row.conclusion()) ? "success" : "failure";
                ClassificationResult r = classifier.classify(new FailureClassifier.Input(
                        row.failedStep(), row.excerpt(), row.flaky(), status));

                boolean tOk = r.errorType() == gold.type();
                boolean fOk = row.flaky() == gold.flaky();
                long millis = (System.nanoTime() - started) / 1_000_000;

                typeOk += tOk ? 1 : 0;
                flakyOk += fOk ? 1 : 0;
                evidenceOk += r.evidenceInLog() ? 1 : 0;
                confusion.merge(gold.type().value() + " -> " + r.errorType().value(), 1, Integer::sum);
                int[] counts = perClass.computeIfAbsent(gold.type(), k -> new int[2]);
                counts[0] += tOk ? 1 : 0;
                counts[1]++;

                reports.add(new EvalRunner.CaseReport(row.branch(), gold.type().value(), r.errorType().value(), tOk,
                        gold.flaky(), row.flaky(), fOk, r.evidenceInLog(),
                        r.rootCause(), r.keyEvidence(), millis, null));
                log.info("{}: gold={} pred={} ok={} flakyOk={} (failed_step={}, {} ms)",
                        row.branch(), gold.type().value(), r.errorType().value(), tOk, fOk,
                        row.failedStep(), millis);
            } catch (Exception e) {
                errors++;
                reports.add(new EvalRunner.CaseReport(row.branch(), gold.type().value(), null, false,
                        gold.flaky(), row.flaky(), false, false, null, null,
                        (System.nanoTime() - started) / 1_000_000, e.toString()));
                log.error("{}: lỗi khi đánh giá", row.branch(), e);
            }
        }

        int evaluated = reports.size() - errors;
        EvalRunner.Report report = new EvalRunner.Report(classifier.modelName(), reports.size(), evaluated, errors,
                ratio(typeOk, evaluated), ratio(flakyOk, evaluated), ratio(evidenceOk, evaluated),
                confusion, reports);

        Path dir = Path.of(reportDir);
        Files.createDirectories(dir);
        Path out = dir.resolve("eval-db-" + classifier.modelName().replaceAll("[^A-Za-z0-9.-]", "_") + ".json");
        Files.writeString(out, jsonMapper.writeValueAsString(report));

        log.info("=== KẾT QUẢ TỪ DB ({}): error_type {}/{} đúng, flaky {}/{} đúng, lỗi {}, bỏ qua {} ===",
                classifier.modelName(), typeOk, evaluated, flakyOk, evaluated, errors, skipped);
        perClass.forEach((type, c) -> log.info("  {}: {}/{} đúng", type.value(), c[0], c[1]));
        log.info("Báo cáo chi tiết: {}", out.toAbsolutePath());
    }

    /** Suy ra nhãn chuẩn từ tên nhánh, ví dụ fail/dep-lock-add -> dependency. */
    private static Gold goldFromBranch(String branch) {
        String name = branch.startsWith("fail/") ? branch.substring("fail/".length()) : branch;
        if (name.startsWith("flaky-")) return new Gold(ErrorType.TEST, true);
        if (name.startsWith("test-")) return new Gold(ErrorType.TEST, false);
        if (name.startsWith("dep-")) return new Gold(ErrorType.DEPENDENCY, false);
        if (name.startsWith("cfg-")) return new Gold(ErrorType.CONFIG_ENV, false);
        if (name.startsWith("build-")) return new Gold(ErrorType.BUILD, false);
        if (name.startsWith("lint-")) return new Gold(ErrorType.LINT, false);
        if (name.startsWith("deploy-")) return new Gold(ErrorType.DEPLOY, false);
        if (name.startsWith("other-")) return new Gold(ErrorType.OTHER, false);
        return null;
    }

    private static double ratio(int part, int total) {
        return total == 0 ? 0.0 : (double) part / total;
    }
}