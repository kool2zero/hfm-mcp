package com.hfmmcp.daemon.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Places a downloaded extract into a folder: unzips archives, copies anything else. */
public final class FileUnpacker {
    private FileUnpacker() {
    }

    /** Returns the files written under {@code targetDir}. Zip entries cannot escape it. */
    public static List<Path> unpack(Path source, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path root = targetDir.toAbsolutePath().normalize();
        List<Path> out = new ArrayList<>();
        if (!isZip(source)) {
            Path dest = root.resolve(source.getFileName().toString());
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            out.add(dest);
            return out;
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(source))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                Path dest = root.resolve(e.getName()).normalize();
                if (!dest.startsWith(root)) {
                    throw new IOException("Zip entry escapes the target folder: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                Files.copy(zip, dest, StandardCopyOption.REPLACE_EXISTING);
                out.add(dest);
            }
        }
        return out;
    }

    private static boolean isZip(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            return in.read() == 'P' && in.read() == 'K';
        }
    }
}
