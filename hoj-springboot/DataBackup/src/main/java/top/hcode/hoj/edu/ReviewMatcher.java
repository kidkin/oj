package top.hcode.hoj.edu;

import java.text.Normalizer;
import java.util.*;

final class ReviewMatcher {
    private static final class Node {
        final Map<Character,Node> next = new HashMap<>();
        final List<String> words = new ArrayList<>();
        Node fallback;
    }
    private final Node root = new Node();

    ReviewMatcher(Collection<String> words) {
        for (String word : words) {
            String normalized = normalize(word);
            if (normalized.isEmpty()) continue;
            Node at = root;
            for (char c : normalized.toCharArray()) at = at.next.computeIfAbsent(c, k -> new Node());
            at.words.add(word);
        }
        Queue<Node> nodes = new ArrayDeque<>();
        for (Node child : root.next.values()) { child.fallback = root; nodes.add(child); }
        while (!nodes.isEmpty()) {
            Node parent = nodes.remove();
            parent.next.forEach((ch, child) -> {
                child.fallback = advance(parent.fallback, ch);
                child.words.addAll(child.fallback.words);
                nodes.add(child);
            });
        }
    }
    static String normalize(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("\u200b","").replace("\ufeff","");
    }
    private Node advance(Node node, char c) {
        while (node != root && !node.next.containsKey(c)) node = node.fallback;
        return node.next.getOrDefault(c,root);
    }
    Map<String,String> find(String text) {
        Map<String,String> result = new LinkedHashMap<>();
        String s = normalize(text);
        Node node = root;
        for (int i=0;i<s.length();i++) {
            node = advance(node,s.charAt(i));
            for (String word : node.words) {
                if (!result.containsKey(word)) result.put(word,s.substring(
                        Math.max(0,i-normalize(word).length()-38),Math.min(s.length(),i+40)));
                if (result.size() >= 20) return result;
            }
        }
        return result;
    }
}
