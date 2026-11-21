package top.hcode.hoj.edu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** Immutable resources distributed with the application. All matching runs offline. */
final class BuiltinReviewDictionary {
    static final BuiltinReviewDictionary INSTANCE=new BuiltinReviewDictionary();
    private static final Pattern HOST=Pattern.compile("[a-z0-9_-]+(?:\\.[a-z0-9_-]+)*");
    private final Map<String,List<String>> catalog=new LinkedHashMap<>();
    private final Map<String,String> labels=new LinkedHashMap<>();
    private final Map<String,List<String>> byHost=new HashMap<>();
    private final ReviewMatcher keywords;
    BuiltinReviewDictionary(){
        labels.put("politics","政治"); labels.put("adult","色情"); labels.put("ads","广告");
        labels.put("weapons","涉枪涉爆"); labels.put("sites","网址");
        Set<String> terms=new LinkedHashSet<>();
        for(String id:labels.keySet()){
            String path="/review/"+id+".txt";
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(
                    Objects.requireNonNull(getClass().getResourceAsStream(path),path),StandardCharsets.UTF_8))){
                List<String> words=reader.lines().map(String::trim).filter(w->!w.isEmpty())
                        .distinct().collect(Collectors.toList());
                if(words.isEmpty()) throw new IllegalStateException("Empty review dictionary: "+id);
                catalog.put(id,words);
                if("sites".equals(id)){
                    for(String word:words){
                        String address=withoutScheme(word);
                        int stop=0;
                        while(stop<address.length() && ":/?#".indexOf(address.charAt(stop))<0) stop++;
                        byHost.computeIfAbsent(address.substring(0,stop),k->new ArrayList<>()).add(word);
                    }
                } else terms.addAll(words);
            } catch(IOException ex){throw new UncheckedIOException(ex);}
        }
        keywords=new ReviewMatcher(terms);
    }
    private static String withoutScheme(String s){
        String address=s.startsWith("https://")?s.substring(8):s.startsWith("http://")?s.substring(7):s;
        return address.endsWith(".")?address.substring(0,address.length()-1):address;
    }
    List<Map<String,Object>> categories(){
        List<Map<String,Object>> result=new ArrayList<>();
        labels.forEach((id,label)->{
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",id);row.put("label",label);
            row.put("count",catalog.get(id).size());result.add(row);
        });
        return result;
    }
    Map<String,Object> page(String category,String query,int page){
        if(!catalog.containsKey(category) || query.length()>128 || page<1 || page>1000)
            throw new EduException(400,"无效的词库查询");
        String keyword=ReviewMatcher.normalize(query.trim());
        List<String> filtered=catalog.get(category).stream().filter(w->w.contains(keyword)).collect(Collectors.toList());
        int from=Math.min((page-1)*40,filtered.size());
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("total",filtered.size());result.put("words",filtered.subList(from,Math.min(from+40,filtered.size())));
        return result;
    }
    private static boolean endsAddress(String text,int end,String suffix){
        if(suffix.isEmpty() || end==text.length()) return true;
        char next=text.charAt(end);
        return !(next>='a' && next<='z' || next>='0' && next<='9' || next=='_' || next=='-');
    }
    Map<String,String> find(String raw){
        String text=ReviewMatcher.normalize(raw);
        Map<String,String> hits=keywords.findNormalized(text);
        Matcher m=HOST.matcher(text);
        while(hits.size()<20 && m.find()){
            String host=m.group();
            if(host.length()>253 || (host.indexOf('.')<0 && host.indexOf('-')<0)) continue;
            // Look up the full host and parent domains, preserving port and path qualifiers.
            for(int offset=0;offset<host.length();){
                String key=host.substring(offset);
                List<String> candidates=byHost.get(key);
                if(candidates!=null) for(String word:candidates){
                    String suffix=withoutScheme(word).substring(key.length());
                    if(text.startsWith(suffix,m.end()) && endsAddress(text,m.end()+suffix.length(),suffix))
                        hits.putIfAbsent(word,text.substring(Math.max(0,m.start()-30),Math.min(text.length(),m.end()+suffix.length()+30)));
                }
                int dot=host.indexOf('.',offset);if(dot<0) break;offset=dot+1;
            }
        }
        return hits;
    }
}
