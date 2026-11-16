import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.zip.*;

/** Signs Cosmotune's parser artifacts; the private key is supplied only to this publication step. */
public class ParserBundleSigner {
    private static final long MAX = 32L * 1024 * 1024;
    private static final String PREFIX = "org/koitharu/kotatsu/parsers/";
    private static final String IMPLEMENTATION = "org.koitharu.kotatsu.parsers.core.ParsersBundleImpl";
    private static boolean contract(String name) {
        String relative = name.split("\\$", 2)[0].substring(PREFIX.length());
        return Set.of("Broken", "ErrorMessages", "InternalParsersApi", "MangaLoaderContext", "MangaParser", "MangaParserAuthProvider", "MangaSourceParser", "ParsersBundle", "ParsersApi", "DirectParsersAccess").contains(relative)
            || relative.startsWith("bitmap.") || relative.startsWith("config.") || relative.startsWith("exception.") || relative.startsWith("webview.")
            || relative.startsWith("model.") && !relative.equals("model.MangaParserSource") && !relative.startsWith("model.MangaParserSourceKt");
    }
    private static Set<String> dexClasses(byte[] bytes) throws IOException {
        if (bytes.length < 112 || !Arrays.equals(Arrays.copyOf(bytes, 4), new byte[]{100,101,120,10})) throw new IOException("Invalid DEX header");
        java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        java.util.function.IntUnaryOperator uint = at -> { if (at < 0 || at > bytes.length - 4) throw new IllegalArgumentException("DEX offset"); int value=data.getInt(at); if(value<0)throw new IllegalArgumentException("DEX value"); return value; };
        if (uint.applyAsInt(32)!=bytes.length || uint.applyAsInt(36)!=112 || uint.applyAsInt(40)!=0x12345678) throw new IOException("Invalid DEX layout");
        int strings=uint.applyAsInt(56), stringsAt=uint.applyAsInt(60), types=uint.applyAsInt(64), typesAt=uint.applyAsInt(68), count=uint.applyAsInt(96), at=uint.applyAsInt(100);
        if(count<1 || count>30000 || at%4!=0 || stringsAt%4!=0 || typesAt%4!=0 || at+count*32L>bytes.length || stringsAt+strings*4L>bytes.length || typesAt+types*4L>bytes.length) throw new IOException("Invalid DEX tables");
        Set<String> names=new HashSet<>();
        for(int i=0;i<count;i++) {
            int type=uint.applyAsInt(at+i*32); if(type>=types)throw new IOException("Invalid DEX type");
            int string=uint.applyAsInt(typesAt+type*4); if(string>=strings)throw new IOException("Invalid DEX string");
            int textAt=uint.applyAsInt(stringsAt+string*4), prefix=0, next;
            do { if(textAt>=bytes.length || prefix++>=5)throw new IOException("Invalid DEX text"); next=bytes[textAt++]&255; } while((next&128)!=0);
            int end=textAt; while(end<Math.min(bytes.length,textAt+2048) && bytes[end]!=0)end++;
            if(end>=Math.min(bytes.length,textAt+2048))throw new IOException("Unterminated DEX class");
            String descriptor=new String(bytes,textAt,end-textAt,StandardCharsets.UTF_8);
            if(!descriptor.startsWith("L") || !descriptor.endsWith(";"))throw new IOException("Invalid DEX class");
            names.add(descriptor.substring(1,descriptor.length()-1).replace('/','.'));
        }
        return names;
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] bounded(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[32768]; int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + (long)count > MAX) throw new IOException("Parser archive exceeds limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("runtime version revision input output-dir channel-properties");
        String runtime = args[0], revision = args[2]; long version = Long.parseLong(args[1]);
        if (!Set.of("jvm", "android").contains(runtime) || version <= 0 || !revision.matches("[a-f0-9]{10,40}"))
            throw new IllegalArgumentException("Invalid bundle identity");
        String secret = System.getenv("PARSER_SIGNING_PRIVATE_KEY");
        if (secret == null || secret.isBlank()) throw new IllegalStateException("PARSER_SIGNING_PRIVATE_KEY is not configured");
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(secret.trim())));
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of(args[5]))) { properties.load(input); }
        PublicKey publicKey = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(properties.getProperty("publicKey"))));
        Path input = Path.of(args[3]), output = Path.of(args[4]); Files.createDirectories(output);
        if (Files.size(input) > MAX) throw new IOException("Parser artifact exceeds limit");
        String fileName = "parsers-" + runtime + "-v1-" + version + (runtime.equals("jvm") ? ".jar" : ".zip");
        Path artifact = output.resolve(fileName);
        Path stagedArtifact = Files.createTempFile(output, ".parser-artifact-", ".part");
        List<String> dex = new ArrayList<>(); Set<String> names = new HashSet<>();
        Set<String> classes = new HashSet<>(); long total = 0;
        try {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(input));
             ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(stagedArtifact))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (name.startsWith("/") || name.contains("\\") || name.contains("..") || !names.add(name))
                    throw new IOException("Unsafe archive entry");
                if (names.size() > 30000) throw new IOException("Too many parser archive entries");
                byte[] bytes = bounded(zip); total += bytes.length;
                if (total > MAX) throw new IOException("Expanded parser artifact exceeds limit");
                if (runtime.equals("jvm")) {
                    if (name.startsWith("META-INF/")) continue;
                    if (!name.startsWith("org/koitharu/kotatsu/parsers/")) throw new IOException("Unexpected parser namespace");
                    if (name.endsWith(".class")) classes.add(name.substring(0,name.length()-6).replace('/','.'));
                } else {
                    if (!name.matches("classes(?:[2-9]|[1-9][0-9])?\\.dex")) throw new IOException("Unexpected DEX entry");
                    if (bytes.length < 8 || !new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("dex\n")) throw new IOException("Invalid DEX header");
                    classes.addAll(dexClasses(bytes));
                    dex.add("{\"name\":\"" + name + "\",\"size\":" + bytes.length + ",\"sha256\":\"" + hash(bytes) + "\"}");
                }
                ZipEntry saved = new ZipEntry(name); saved.setTime(0); target.putNextEntry(saved); target.write(bytes); target.closeEntry();
            }
        }
        if (!classes.contains(IMPLEMENTATION) || classes.stream().anyMatch(name -> !name.startsWith(PREFIX.replace('/','.')) || contract(name))) throw new IOException("Missing parser implementation or contract/namespace replacement");
        if(runtime.equals("android") && (dex.isEmpty() || dex.size()>16)) throw new IOException("Invalid parser DEX count");
        if(runtime.equals("android")) for(int i=1;i<=dex.size();i++) if(!names.contains(i==1?"classes.dex":"classes"+i+".dex")) throw new IOException("Incomplete parser DEX sequence");
        byte[] artifactBytes = Files.readAllBytes(stagedArtifact);
        if (artifactBytes.length > MAX) throw new IOException("Parser artifact exceeds app archive limit");
        if (Files.exists(artifact) && !Arrays.equals(Files.readAllBytes(artifact), artifactBytes)) throw new IOException("A parser version is immutable; use a newer reviewed version");
        String payload = "{\"format\":1,\"api\":1,\"version\":" + version + ",\"revision\":\"" + revision + "\",\"runtime\":\"" + runtime +
            "\",\"minSdk\":26,\"file\":\"" + fileName + "\",\"size\":" + artifactBytes.length + ",\"sha256\":\"" + hash(artifactBytes) + "\"" +
            (runtime.equals("android") ? ",\"dex\":[" + String.join(",", dex) + "]" : "") + "}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withECDSA"); signer.initSign(key); signer.update(bytes); byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withECDSA"); verifier.initVerify(publicKey); verifier.update(bytes);
        if (!verifier.verify(signature)) throw new GeneralSecurityException("Publication key differs from the app's pinned verification key");
        String envelope = "{\"payload\":\"" + Base64.getEncoder().encodeToString(bytes) + "\",\"signature\":\"" + Base64.getEncoder().encodeToString(signature) + "\"}\n";
        Path manifest = output.resolve("parsers-" + runtime + "-v1.json");
        Path stagedManifest = Files.createTempFile(output, ".parser-manifest-", ".part");
        try {
            Files.writeString(stagedManifest, envelope, StandardCharsets.UTF_8);
            Files.move(stagedArtifact, artifact, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.move(stagedManifest, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(stagedManifest); }
        System.out.println("Signed " + runtime + " parser bundle at " + revision + " (" + artifactBytes.length + " bytes)");
        } finally { Files.deleteIfExists(stagedArtifact); }
    }
}
