package com.nbl.cfa.repo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Component
public class RepoRegistry {

    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") ;
    private static final Pattern IMAGE = Pattern.compile("ghcr.io/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") ;
    private final Map<String, RepoConfig> configs;

    public RepoRegistry(@Value("${cfa.repos.file:config/repos.yml}") String file) {
        this(Path.of(file));
    }

    public RepoRegistry(Path file) {
        this.configs = load(file);
    }

    public Optional<RepoConfig> find(String repoFullName) {
        return Optional.ofNullable(configs.get(repoFullName));
    }

    private static Map<String, RepoConfig> load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("Không tìm thấy repo registry: " + file);
            }
            Object parsed = new Yaml().load(Files.readString(file));
            if (!(parsed instanceof Map<?, ?> root)) {
                throw new IllegalStateException("Repo registry phải là YAML object");
            }
            Object rawRepos = root.get("repos");
            if (!(rawRepos instanceof List<?> repos) || repos.isEmpty()) {
                throw new IllegalStateException("Repo registry phải có ít nhất một repo");
            }
            Map<String, RepoConfig> result = new java.util.LinkedHashMap<>();
            Set<Integer> ports = new HashSet<>();
            for (Object rawRepo : repos) {
                if (!(rawRepo instanceof Map<?, ?> repo)) {
                    throw new IllegalStateException("Mỗi repo registry phải là YAML object");
                }
                RepoConfig config = parse(repo);
                if (result.put(config.repoFullName(), config) != null) {
                    throw new IllegalStateException("Repo bị trùng trong registry: " + config.repoFullName());
                }
                if (!ports.add(config.deploy().stagingPort())) {
                    throw new IllegalStateException("staging_port bị trùng: " + config.deploy().stagingPort());
                }
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException e) {
            if (e instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("Không đọc được repo registry: " + file, e);
        }
    }

    private static RepoConfig parse(Map<?, ?> raw) {
        String name = string(raw, "repo_full_name");
        if (!REPO.matcher(name).matches()) {
            throw new IllegalStateException("repo_full_name không hợp lệ: " + name);
        }
        String profile = string(raw, "llm_profile").toLowerCase();
        if (!profile.equals("local") && !profile.equals("cloud")) {
            throw new IllegalStateException("llm_profile không hợp lệ cho " + name);
        }
        Map<?, ?> features = object(raw, "features");
        Map<?, ?> deploy = object(raw, "deploy");
        Map<?, ?> test = object(raw, "test");
        String image = string(deploy, "image_repository");
        if (!IMAGE.matcher(image).matches()) {
            throw new IllegalStateException("image_repository không hợp lệ cho " + name);
        }
        int port = number(deploy, "staging_port");
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("staging_port không hợp lệ cho " + name);
        }
        String stagingUrl = string(deploy, "staging_url");
        if (!stagingUrl.startsWith("http://127.0.0.1:")) {
            throw new IllegalStateException("staging_url phải là local compose URL cho " + name);
        }
        Path stagingDir = safePath(string(deploy, "staging_dir"), "staging_dir", name);
        Path checkoutPath = checkoutPath(string(test, "checkout_path"), name);
        String aiTestsDir = relativePath(string(test, "ai_tests_dir"), "ai_tests_dir", name);
        String appDir = relativePath(string(test, "app_dir"), "app_dir", name);
        return new RepoConfig(name, bool(raw, "enabled"), profile,
                new RepoConfig.Features(bool(features, "triage"), bool(features, "deploy"), bool(features, "test")),
                new RepoConfig.Deploy(string(deploy, "target"), image, stagingDir, port, stagingUrl),
                new RepoConfig.Test(checkoutPath, appDir, aiTestsDir, string(test, "base_branch")));
    }

    private static Path safePath(String value, String field, String repo) {
        if (value.isBlank() || value.contains("..")) {
            throw new IllegalStateException(field + " không hợp lệ cho " + repo);
        }
        return Path.of(value).normalize();
    }

    private static Path checkoutPath(String value, String repo) {
        if (value.isBlank() || Path.of(value).isAbsolute() || value.contains("\\")) {
            throw new IllegalStateException("checkout_path không hợp lệ cho " + repo);
        }
        return Path.of(value).normalize();
    }

    private static String relativePath(String value, String field, String repo) {
        if (value.isBlank() || value.startsWith("/") || value.contains("..")) {
            throw new IllegalStateException(field + " không hợp lệ cho " + repo);
        }
        return value.replace('\\', '/').replaceAll("^/+|/+$", "");
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Thiếu trường " + key);
        }
        return text.trim();
    }

    private static boolean bool(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalStateException("Trường " + key + " phải là boolean");
        }
        return flag;
    }

    private static int number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("Trường " + key + " phải là number");
        }
        return number.intValue();
    }

    private static Map<?, ?> object(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalStateException("Thiếu object " + key);
        }
        return object;
    }
}
