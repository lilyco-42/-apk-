package com.acooldog.toolbox.route.data;

import android.content.Context;

import com.acooldog.toolbox.route.domain.model.RouteDefinition;
import com.acooldog.toolbox.route.domain.model.RoutePoint;
import com.acooldog.toolbox.route.domain.model.RouteShareInfo;
import com.acooldog.toolbox.route.domain.repository.RouteRepository;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class FileRouteRepository implements RouteRepository {
    static final int MAX_ROUTE_BYTES = 8 * 1024 * 1024;
    private final File routeDirectory;
    private final RouteJsonCodec routeJsonCodec;

    public FileRouteRepository(Context context) {
        this(resolveDirectory(context));
    }

    FileRouteRepository(File directory) {
        routeDirectory = directory;
        routeJsonCodec = new RouteJsonCodec();
    }

    private static File resolveDirectory(Context context) {
        File external = context.getExternalFilesDir("routes");
        return external != null ? external : new File(context.getFilesDir(), "routes");
    }

    @Override
    public synchronized List<RouteDefinition> getRoutes() throws IOException {
        ensureDirectory();
        File[] files = routeDirectory.listFiles((dir, name) -> name.endsWith(".route.json") || name.endsWith(".json"));
        if (files == null) {
            throw new IOException("Unable to list route directory");
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        List<RouteDefinition> routes = new ArrayList<>();
        for (File file : files) {
            routes.add(readRoute(resolveRouteFile(file.getName())));
        }
        return routes;
    }

    @Override
    public synchronized RouteDefinition getRoute(String routeId) throws IOException {
        File routeFile = resolveRouteFile(routeId);
        if (!routeFile.exists()) {
            return null;
        }
        return readRoute(routeFile);
    }

    @Override
    public synchronized RouteDefinition saveRoute(String routeName, List<RoutePoint> points) throws IOException {
        return saveRoute(routeName, points, RouteShareInfo.NONE);
    }

    @Override
    public synchronized RouteDefinition saveRoute(String routeName, List<RoutePoint> points, RouteShareInfo shareInfo) throws IOException {
        ensureDirectory();
        long now = System.currentTimeMillis();
        String safeName = sanitizeFileName(routeName);
        File routeFile = newRouteFile(safeName);
        RouteDefinition routeDefinition = new RouteDefinition(routeFile.getName(), routeName, now, now, points, routeFile, shareInfo);
        writeText(routeFile, encodeRoute(routeDefinition));
        return routeDefinition;
    }

    @Override
    public synchronized RouteDefinition updateRoute(String routeId, String routeName, List<RoutePoint> points, RouteShareInfo shareInfo) throws IOException {
        ensureDirectory();
        File sourceFile = resolveRouteFile(routeId);
        long now = System.currentTimeMillis();
        long createdAt = now;
        RouteShareInfo resolvedShareInfo = shareInfo == null ? RouteShareInfo.NONE : shareInfo;
        if (sourceFile.exists()) {
            RouteDefinition existingRoute = readRoute(sourceFile);
            createdAt = existingRoute.getCreatedAt();
            if (shareInfo == null) {
                resolvedShareInfo = existingRoute.getShareInfo();
            }
        }

        if (!sourceFile.isFile()) {
            throw new IOException("Route no longer exists");
        }
        // The file name is the route ID. Renaming its display name must not change that ID.
        File targetFile = sourceFile;
        RouteDefinition routeDefinition = new RouteDefinition(
                targetFile.getName(),
                routeName,
                createdAt,
                now,
                points,
                targetFile,
                resolvedShareInfo
        );
        writeText(targetFile, encodeRoute(routeDefinition));

        return routeDefinition;
    }

    @Override
    public synchronized RouteDefinition importRoute(String displayName, InputStream inputStream) throws IOException {
        ensureDirectory();
        String content = readText(inputStream);
        String safeName = sanitizeFileName(displayName == null || displayName.trim().isEmpty() ? "imported-route" : displayName);
        File routeFile = newRouteFile(safeName);
        RouteDefinition importedRoute;
        try {
            importedRoute = routeJsonCodec.decode(routeFile.getName(), displayName, content, routeFile);
        } catch (JSONException exception) {
            throw new IOException("Unable to parse route file", exception);
        }
        if (!importedRoute.hasEnoughPoints()) {
            throw new IOException("Route must contain at least two points");
        }
        // 列表显示的是 JSON 内部的 name，同一条路线导入两次会出现两条一模一样的条目，
        // 用户无法区分。这里对重名的自动加 (2)、(3) 后缀。
        String uniqueName = uniqueRouteName(importedRoute.getName(), routeFile.getName());
        if (!uniqueName.equals(importedRoute.getName())) {
            importedRoute = new RouteDefinition(
                    importedRoute.getId(), uniqueName,
                    importedRoute.getCreatedAt(), importedRoute.getUpdatedAt(),
                    importedRoute.getPoints(), importedRoute.getFile(),
                    importedRoute.getShareInfo());
        }
        writeText(routeFile, encodeRoute(importedRoute));
        return importedRoute;
    }

    /** 在现有路线里找同名项，重名则返回「名字 (2)」这样的唯一名。 */
    private String uniqueRouteName(String name, String selfFile) {
        String base = name == null || name.trim().isEmpty() ? "未命名路线" : name.trim();
        java.util.Set<String> existing = new java.util.HashSet<>();
        File[] files = routeDirectory.listFiles((dir, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                if (file.getName().equals(selfFile)) {
                    continue;
                }
                try {
                    existing.add(readRoute(file).getName());
                } catch (IOException ignored) {
                    // 读不出来的文件不参与重名判断
                }
            }
        }
        if (!existing.contains(base)) {
            return base;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = base + " (" + i + ")";
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

    @Override
    public synchronized void deleteRoute(String routeId) throws IOException {
        File routeFile = resolveRouteFile(routeId);
        if (routeFile.exists() && !routeFile.delete()) {
            throw new IOException("Unable to delete route file");
        }
    }

    private RouteDefinition readRoute(File routeFile) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(routeFile)) {
            return routeJsonCodec.decode(routeFile.getName(), routeFile.getName(), readText(inputStream), routeFile);
        } catch (JSONException exception) {
            throw new IOException("Unable to decode route file: " + routeFile.getName(), exception);
        }
    }

    private String encodeRoute(RouteDefinition routeDefinition) throws IOException {
        try {
            return routeJsonCodec.encode(routeDefinition);
        } catch (JSONException exception) {
            throw new IOException("Unable to encode route", exception);
        }
    }

    private String readText(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            if (outputStream.size() + length > MAX_ROUTE_BYTES) {
                throw new IOException("Route file exceeds the 8 MiB limit");
            }
            outputStream.write(buffer, 0, length);
        }
        return outputStream.toString(StandardCharsets.UTF_8.name());
    }

    private void writeText(File routeFile, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ROUTE_BYTES) {
            throw new IOException("Route file exceeds the 8 MiB limit");
        }
        File temporary = File.createTempFile("route-", ".tmp", routeDirectory);
        try {
            try (FileOutputStream outputStream = new FileOutputStream(temporary)) {
                outputStream.write(bytes);
                outputStream.getFD().sync();
            }
            // Same-directory atomic replacement keeps the previous route intact on write failure.
            Files.move(temporary.toPath(), routeFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private File newRouteFile(String safeName) throws IOException {
        // 用户选的文件名往往已经带 .json / .route.json，
        // 不剥掉就会出现「xxx.route.json.route.json」这种双后缀。
        String base = stripRouteExtension(safeName);
        File preferred = new File(routeDirectory, base + ".route.json");
        if (!preferred.exists()) {
            return resolveRouteFile(preferred.getName());
        }
        return resolveRouteFile(base + "_" + UUID.randomUUID() + ".route.json");
    }

    /** 去掉末尾的 .route.json / .json，可重复调用。 */
    private static String stripRouteExtension(String name) {
        String value = name == null ? "" : name.trim();
        boolean changed = true;
        while (changed) {
            changed = false;
            String lower = value.toLowerCase();
            if (lower.endsWith(".route.json")) {
                value = value.substring(0, value.length() - ".route.json".length());
                changed = true;
            } else if (lower.endsWith(".json")) {
                value = value.substring(0, value.length() - ".json".length());
                changed = true;
            }
        }
        return value.isEmpty() ? "route" : value;
    }

    private File resolveRouteFile(String routeId) throws IOException {
        if (routeId == null || routeId.isEmpty() || routeId.contains("/")
                || routeId.contains("\\") || !routeId.endsWith(".json")) {
            throw new IOException("Invalid route ID");
        }
        File file = new File(routeDirectory, routeId).getCanonicalFile();
        if (!routeDirectory.getCanonicalFile().equals(file.getParentFile())) {
            throw new IOException("Route must be inside the route directory");
        }
        return file;
    }

    private void ensureDirectory() throws IOException {
        if (!routeDirectory.exists() && !routeDirectory.mkdirs()) {
            throw new IOException("Unable to create route directory");
        }
    }

    private String sanitizeFileName(String routeName) {
        String normalized = Normalizer.normalize(routeName == null ? "" : routeName.trim(), Normalizer.Form.NFKC)
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
        if (normalized.isEmpty()) {
            return "route_" + System.currentTimeMillis();
        }
        // Leave room for the collision suffix and extension within filesystem name limits.
        int end = normalized.offsetByCodePoints(0, Math.min(48, normalized.codePointCount(0, normalized.length())));
        return normalized.substring(0, end);
    }
}
