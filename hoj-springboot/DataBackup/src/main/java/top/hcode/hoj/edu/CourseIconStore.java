package top.hcode.hoj.edu;

import org.springframework.web.multipart.MultipartFile;
import top.hcode.hoj.utils.Constants;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Iterator;
import java.util.UUID;

/** Store decoded PNG/JPEG pixels under generated names; original paths/metadata are not retained. */
class CourseIconStore {
    private final Path folder;
    CourseIconStore() { this(Paths.get(Constants.File.COURSE_ICON_FOLDER.getPath())); }
    CourseIconStore(Path folder) { this.folder = folder; }

    String save(MultipartFile image) {
        if (image == null || image.isEmpty() || image.getSize() > 2 * 1024 * 1024)
            throw new EduException(400, "请选择不超过2MB的PNG或JPEG图片");
        BufferedImage pixels;
        try (java.io.InputStream input = image.getInputStream(); ImageInputStream stream = ImageIO.createImageInputStream(input)) {
            if (stream == null) throw new EduException(400, "无法读取图片");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new EduException(400, "图片内容须为PNG或JPEG格式");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!"PNG".equalsIgnoreCase(format) && !"JPEG".equalsIgnoreCase(format))
                    throw new EduException(400, "仅支持PNG或JPEG格式");
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 2048 || height > 2048)
                    throw new EduException(400, "图片长宽须在1至2048像素之间");
                pixels = reader.read(0);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException error) { throw new EduException(400, "图片损坏或格式无效"); }
        String filename = "course_" + UUID.randomUUID().toString().replace("-", "") + ".png";
        try {
            Files.createDirectories(folder);
            try (OutputStream output = Files.newOutputStream(folder.resolve(filename), StandardOpenOption.CREATE_NEW)) {
                if (!ImageIO.write(pixels, "png", output)) throw new IOException("PNG encoder unavailable");
            }
        } catch (IOException error) {
            delete(filename);
            throw new EduException(500, "课程图标保存失败，请稍后重试");
        }
        return filename;
    }

    void delete(String filename) {
        if (filename == null || !filename.matches("course_[0-9a-f]{32}\\.png")) return;
        try { Files.deleteIfExists(folder.resolve(filename)); }
        catch (IOException ignored) { /* A committed course must remain usable if an obsolete icon cannot be removed. */ }
    }
}
