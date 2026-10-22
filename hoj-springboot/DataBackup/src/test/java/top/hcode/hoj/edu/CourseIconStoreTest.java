package top.hcode.hoj.edu;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

class CourseIconStoreTest {
    @TempDir Path folder;

    private byte[] png(int width, int height) throws Exception {
        BufferedImage pixels = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        pixels.setRGB(0, 0, 0xff4285f4);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(pixels, "png", output); return output.toByteArray();
    }
    @Test void storesDecodedPixelsUnderAGeneratedFilename() throws Exception {
        CourseIconStore store = new CourseIconStore(folder);
        String filename = store.save(new MockMultipartFile("image", "../../unsafe.svg", "image/png", png(32, 32)));
        assertTrue(filename.matches("course_[a-f0-9]{32}\\.png"));
        BufferedImage actual = ImageIO.read(folder.resolve(filename).toFile());
        assertEquals(32, actual.getWidth()); assertEquals(0xff4285f4, actual.getRGB(0, 0));
    }
    @Test void svgAndFakeImagePayloadsAreRejectedBeforeWriting() {
        CourseIconStore store = new CourseIconStore(folder);
        assertEquals(400, assertThrows(EduException.class, () -> store.save(new MockMultipartFile("image", "icon.png", "image/png", "<svg onload='alert(1)'/>".getBytes()))).getStatus());
        assertThrows(EduException.class, () -> store.save(new MockMultipartFile("image", new byte[0])));
        try (java.util.stream.Stream<Path> paths = Files.list(folder)) { assertEquals(0, paths.count()); }
        catch (java.io.IOException e) { fail(e); }
    }
    @Test void oversizedFilesAndDimensionsAreRejectedBeforePixelAllocation() throws Exception {
        CourseIconStore store = new CourseIconStore(folder);
        assertThrows(EduException.class, () -> store.save(new MockMultipartFile("image", new byte[2 * 1024 * 1024 + 1])));
        assertThrows(EduException.class, () -> store.save(new MockMultipartFile("image", png(2049, 1))));
    }
    @Test void deletionIsLimitedToGeneratedIconNames() throws Exception {
        Path existing = folder.resolve("keep.txt"); Files.write(existing, new byte[]{1});
        CourseIconStore store = new CourseIconStore(folder);
        store.delete("keep.txt"); store.delete("../keep.txt");
        assertTrue(Files.exists(existing));
        String icon = store.save(new MockMultipartFile("image", png(1, 1)));
        store.delete(icon); store.delete(icon);
        assertFalse(Files.exists(folder.resolve(icon)));
    }
}
