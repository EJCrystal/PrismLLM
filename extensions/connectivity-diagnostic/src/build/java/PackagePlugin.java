import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** JDK-only build tooling. This source is not a resource and never ships in plugin.jar. */
final class PackagePlugin {
    private static final String PROVIDER = "dev.soven.prismllm.extensions.connectivity.ConnectivityDiagnosticPlugin";
    private static final String SERVICE = "META-INF/services/dev.soven.prismllm.plugin.sdk.DiagnosticPlugin";
    private static final String MAVEN_METADATA = "META-INF/maven/dev.soven/prism-connectivity/";
    // Local DOS timestamp avoids timezone-dependent ZIP bytes and extended timestamp fields.
    private static final LocalDateTime ZIP_TIME = LocalDateTime.of(1980, 1, 1, 0, 0, 2);

    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException("Expected manifest, UI, JAR, package, catalog, id and version");
        Path manifest = Path.of(args[0]);
        Path ui = Path.of(args[1]);
        Path jar = Path.of(args[2]);
        Path archive = Path.of(args[3]);
        Path catalog = Path.of(args[4]);
        String id = args[5];
        String version = args[6];
        if (!id.equals("prism.connectivity") || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException("Invalid official plugin identity");
        }
        String filename = archive.getFileName().toString();
        if (!filename.equals("prism-connectivity-" + version + ".prismplugin")) {
            throw new IllegalArgumentException("Package filename/version mismatch");
        }
        String manifestText = Files.readString(manifest, StandardCharsets.UTF_8);
        requireManifestValue(manifestText, "id", id);
        requireManifestValue(manifestText, "version", version);
        validateJar(jar, version);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", manifestText.getBytes(StandardCharsets.UTF_8));
        entries.put("ui.json", Files.readAllBytes(ui));
        entries.put("plugin.jar", Files.readAllBytes(jar));
        Files.createDirectories(archive.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(archive.toAbsolutePath().getParent(), "prism-package-", ".tmp");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                for (Map.Entry<String, byte[]> item : entries.entrySet()) {
                    byte[] bytes = item.getValue();
                    CRC32 crc = new CRC32();
                    crc.update(bytes);
                    ZipEntry entry = new ZipEntry(item.getKey());
                    entry.setTimeLocal(ZIP_TIME);
                    entry.setMethod(ZipEntry.STORED);
                    entry.setSize(bytes.length);
                    entry.setCompressedSize(bytes.length);
                    entry.setCrc(crc.getValue());
                    zip.putNextEntry(entry);
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
            makeGatewayReadable(temporary);
            Files.move(temporary, archive, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
        String catalogJson = """
                {
                  "schemaVersion": 1,
                  "packages": [
                    {"id": "%s", "version": "%s", "file": "%s", "sha256": "%s"}
                  ]
                }
                """.formatted(id, version, filename, digest);
        Files.writeString(catalog, catalogJson, StandardCharsets.UTF_8);
        System.out.println("Trusted package: " + archive.getFileName() + " (sha256 " + digest + ")");
        System.out.println("Local catalog: " + catalog.getFileName());
    }

    private static void makeGatewayReadable(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException unsupported) {
            if (!path.toFile().setReadable(true, false)) {
                throw new IOException("Could not make plugin package readable by gateway deployments", unsupported);
            }
        }
    }

    private static void requireManifestValue(String json, String key, String expected) {
        var matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"\\\\]*)\"").matcher(json);
        if (!matcher.find() || !matcher.group(1).equals(expected) || matcher.find()) {
            throw new IllegalArgumentException("Manifest " + key + " must match the independent Maven artifact");
        }
    }

    private static void validateJar(Path path, String version) throws IOException {
        try (ZipFile jar = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            var names = new HashSet<String>();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate JAR entry");
                if (entry.isDirectory()) continue;
                boolean pluginClass = name.startsWith("dev/soven/prismllm/extensions/connectivity/") && name.endsWith(".class");
                boolean metadata = name.equals("META-INF/MANIFEST.MF") || name.equals(SERVICE)
                        || name.equals("META-INF/prism-connectivity.kotlin_module")
                        || name.equals(MAVEN_METADATA + "pom.xml") || name.equals(MAVEN_METADATA + "pom.properties");
                if (!pluginClass && !metadata) {
                    throw new IllegalArgumentException("Plugin JAR contains non-plugin code or an unexpected resource: " + name);
                }
            }
            ZipEntry service = jar.getEntry(SERVICE);
            if (service == null || !new String(jar.getInputStream(service).readAllBytes(), StandardCharsets.UTF_8).trim().equals(PROVIDER)) {
                throw new IllegalArgumentException("Expected exactly one DiagnosticPlugin service provider");
            }
            if (jar.getEntry(PROVIDER.replace('.', '/') + ".class") == null) {
                throw new IllegalArgumentException("Missing official DiagnosticPlugin implementation");
            }
            ZipEntry coordinates = jar.getEntry(MAVEN_METADATA + "pom.properties");
            if (coordinates == null) throw new IllegalArgumentException("Missing Maven plugin coordinates");
            Properties properties = new Properties();
            try (var input = jar.getInputStream(coordinates)) {
                properties.load(input);
            }
            if (!version.equals(properties.getProperty("version")) || !"prism-connectivity".equals(properties.getProperty("artifactId"))) {
                throw new IllegalArgumentException("Plugin JAR/version mismatch");
            }
        }
    }
}
