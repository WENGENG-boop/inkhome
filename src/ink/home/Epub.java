package ink.home;

import android.net.Uri;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilderFactory;

final class Epub implements Content {
    private static final Pattern NCX_POINT = Pattern.compile(
            "<navLabel[^>]*>\\s*<text[^>]*>(.*?)</text>\\s*</navLabel>\\s*<content[^>]*src=[\"']([^\"']+)[\"']", Pattern.DOTALL);
    private static final Pattern NAV_LINK = Pattern.compile(
            "<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODING = Pattern.compile("encoding=[\"']([\\w-]+)[\"']");

    /** What the package document says about the book. */
    static final class Package {
        String title = "";
        String author = "";
        String coverPath;
        String tocPath;
        boolean tocIsNav;
        final List<String> spine = new ArrayList<>();
    }

    private final File root;
    private final Package pkg;
    private final List<TocEntry> toc = new ArrayList<>();

    Epub(File file, File cacheDir) throws Exception {
        root = cacheDir;
        try (ZipFile zip = new ZipFile(file)) {
            pkg = readPackage(zip);
            File done = new File(root, ".done");
            if (!done.exists()) {
                extract(zip, root);
                done.createNewFile();
            }
        }
        readToc();
    }

    static Package readPackage(ZipFile zip) throws Exception {
        Document container = parse(read(zip, "META-INF/container.xml"));
        String opfPath = ((Element) container.getElementsByTagNameNS("*", "rootfile").item(0)).getAttribute("full-path");
        Document opf = parse(read(zip, opfPath));
        String dir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

        Package result = new Package();
        result.title = firstText(opf, "title");
        result.author = firstText(opf, "creator");

        Map<String, String> hrefs = new HashMap<>();
        String coverId = null;
        NodeList metas = opf.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("cover".equals(meta.getAttribute("name"))) coverId = meta.getAttribute("content");
        }
        NodeList items = opf.getElementsByTagNameNS("*", "item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String id = item.getAttribute("id");
            String path = resolve(dir, item.getAttribute("href"));
            String properties = item.getAttribute("properties");
            String type = item.getAttribute("media-type");
            hrefs.put(id, path);
            if (properties.contains("cover-image") || id.equals(coverId)) result.coverPath = path;
            if (result.coverPath == null && type.startsWith("image/") && id.toLowerCase().contains("cover")) {
                result.coverPath = path;
            }
            if (properties.contains("nav")) {
                result.tocPath = path;
                result.tocIsNav = true;
            }
        }
        Element spine = (Element) opf.getElementsByTagNameNS("*", "spine").item(0);
        if (result.tocPath == null && spine != null) result.tocPath = hrefs.get(spine.getAttribute("toc"));
        NodeList refs = opf.getElementsByTagNameNS("*", "itemref");
        for (int i = 0; i < refs.getLength(); i++) {
            String path = hrefs.get(((Element) refs.item(i)).getAttribute("idref"));
            if (path != null) result.spine.add(path);
        }
        return result;
    }

    @Override
    public int chapters() {
        return pkg.spine.size();
    }

    @Override
    public File base(int chapter) {
        return new File(root, pkg.spine.get(chapter));
    }

    @Override
    public String html(int chapter) throws IOException {
        byte[] bytes = Files.readAllBytes(base(chapter).toPath());
        String head = new String(bytes, 0, Math.min(200, bytes.length), StandardCharsets.ISO_8859_1);
        Matcher matcher = ENCODING.matcher(head);
        Charset charset = StandardCharsets.UTF_8;
        if (matcher.find() && Charset.isSupported(matcher.group(1))) charset = Charset.forName(matcher.group(1));
        return new String(bytes, charset).replaceFirst("^\\s*<\\?xml[^>]*\\?>", "");
    }

    @Override
    public List<TocEntry> toc() {
        return toc;
    }

    private void readToc() {
        if (pkg.tocPath != null) {
            try {
                String source = new String(Files.readAllBytes(new File(root, pkg.tocPath).toPath()), StandardCharsets.UTF_8);
                String dir = pkg.tocPath.contains("/") ? pkg.tocPath.substring(0, pkg.tocPath.lastIndexOf('/') + 1) : "";
                Matcher matcher = (pkg.tocIsNav ? NAV_LINK : NCX_POINT).matcher(source);
                while (matcher.find()) {
                    String href = matcher.group(pkg.tocIsNav ? 1 : 2);
                    String label = matcher.group(pkg.tocIsNav ? 2 : 1).replaceAll("<[^>]+>", "").trim();
                    int hash = href.indexOf('#');
                    String path = resolve(dir, hash >= 0 ? href.substring(0, hash) : href);
                    int chapter = pkg.spine.indexOf(path);
                    if (chapter >= 0 && !label.isEmpty()) toc.add(new TocEntry(label, chapter));
                }
            } catch (IOException ignored) {
            }
        }
        if (toc.isEmpty()) {
            for (int i = 0; i < pkg.spine.size(); i++) toc.add(new TocEntry("第 " + (i + 1) + " 节", i));
        }
    }

    private static void extract(ZipFile zip, File target) throws IOException {
        String rootPath = target.getCanonicalPath() + File.separator;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        byte[] buffer = new byte[16384];
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            File out = new File(target, entry.getName());
            if (!out.getCanonicalPath().startsWith(rootPath)) continue;
            if (entry.isDirectory()) {
                out.mkdirs();
                continue;
            }
            out.getParentFile().mkdirs();
            try (InputStream in = zip.getInputStream(entry); FileOutputStream stream = new FileOutputStream(out)) {
                int n;
                while ((n = in.read(buffer)) > 0) stream.write(buffer, 0, n);
            }
        }
    }

    static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("missing " + name);
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static String firstText(Document document, String tag) {
        NodeList nodes = document.getElementsByTagNameNS("*", tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    private static String resolve(String dir, String href) {
        List<String> parts = new ArrayList<>();
        for (String part : (dir + Uri.decode(href)).split("/")) {
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else if (!part.isEmpty() && !part.equals(".")) {
                parts.add(part);
            }
        }
        return String.join("/", parts);
    }
}
