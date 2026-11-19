package top.hcode.hoj.edu;

import java.util.*;
import java.util.regex.*;

/** Compare token shingles. Keep source line positions so teachers can inspect the actual text. */
final class SolutionFingerprint {
    private static final int WINDOW=10, MIN_TOKENS=45;
    private static final Set<String> KEYWORDS=new HashSet<>(Arrays.asList((
            "if else elif for while do break continue return switch case default true false null void int long char bool double float "+
            "class struct static const auto vector string map set sort cin cout scanf printf new delete try catch throw "+
            "def in range len print input enumerate zip list dict append lambda and or not True False None yield with as "+
            "public private protected import from main self this").split(" ")));
    private static final Pattern TOKEN=Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*|\\d+(?:\\.\\d+)?|>>|<<|==|!=|<=|>=|\\+\\+|--|&&|\\|\\||\\+=|-=|\\*=|/=|//|\\*\\*|->|::|[^\\s]");
    private final Map<Long,Integer> positions=new HashMap<>();
    private final List<Integer> lines=new ArrayList<>();
    private final String[] source;

    SolutionFingerprint(String code, boolean python) {
        source=code.split("\\r?\\n",-1);
        List<String> tokens=new ArrayList<>();
        boolean comment=false;
        String triple=null;
        for (int n=0;n<source.length;n++) {
            String line=source[n];
            String trim=line.trim();
            if (!comment && triple==null && (python ? trim.startsWith("import ") || trim.startsWith("from ") :
                    trim.startsWith("#include") || trim.startsWith("using namespace "))) continue;
            Matcher lex=TOKEN.matcher(line);
            int pos=0;
            while(pos<line.length()) {
                if(comment) {
                    int end=line.indexOf("*/",pos);
                    if(end<0) break;
                    comment=false; pos=end+2; continue;
                }
                if(triple!=null) {
                    int end=line.indexOf(triple,pos);
                    if(end<0) break;
                    pos=end+3; triple=null; continue;
                }
                char c=line.charAt(pos);
                if(Character.isWhitespace(c)){ pos++; continue; }
                if(python ? c=='#' : line.startsWith("//",pos)) break;
                if(!python && line.startsWith("/*",pos)) { comment=true; pos+=2; continue; }
                String token;
                if(c=='"' || c=='\'') {
                    if(python && pos+2<line.length() && line.charAt(pos+1)==c && line.charAt(pos+2)==c) {
                        triple=line.substring(pos,pos+3); pos+=3;
                    } else {
                        pos++;
                        while(pos<line.length()){
                            char next=line.charAt(pos++);
                            if(next=='\\' && pos<line.length()) pos++;
                            else if(next==c) break;
                        }
                    }
                    token=":literal";
                } else {
                    lex.region(pos,line.length());
                    if(!lex.lookingAt()){ pos++; continue; }
                    token=lex.group(); pos=lex.end();
                    if((Character.isLetter(c)||c=='_') && !KEYWORDS.contains(token)) token=":name";
                }
                tokens.add(token); lines.add(n);
            }
        }
        for(int start=0;start+WINDOW<=tokens.size();start++){
            long hash=1469598103934665603L;
            for(int i=start;i<start+WINDOW;i++) hash=(hash^tokens.get(i).hashCode())*1099511628211L;
            positions.putIfAbsent(hash,start);
        }
    }
    boolean substantial(){ return lines.size()>=MIN_TOKENS && positions.size()>=25; }
    Set<Long> keys(){ return positions.keySet(); }
    static int percent(int shared,int left,int right){ return left+right==0 ? 0 : (int)Math.round(200.0*shared/(left+right)); }
    List<Map<String,Object>> evidence(SolutionFingerprint other){
        List<Map.Entry<Long,Integer>> ordered=new ArrayList<>(positions.entrySet());
        ordered.sort(Map.Entry.comparingByValue());
        List<Map<String,Object>> blocks=new ArrayList<>();
        int end=-1;
        for(Map.Entry<Long,Integer> entry:ordered){
            Integer theirs=other.positions.get(entry.getKey()); int ours=entry.getValue();
            if(theirs==null || ours<=end) continue;
            int last=ours+WINDOW-1, otherLast=theirs+WINDOW-1;
            blocks.add(pairExcerpt(ours,last,other,theirs,otherLast));
            end=last;
            if(blocks.size()==6) break;
        }
        return blocks;
    }
    private Map<String,Object> pairExcerpt(int a,int end,SolutionFingerprint other,int b,int bEnd){
        Map<String,Object> block=new LinkedHashMap<>(); block.put("left",excerpt(a,end)); block.put("right",other.excerpt(b,bEnd)); return block;
    }
    private Map<String,Object> excerpt(int start,int end){
        int first=lines.get(start),last=lines.get(end);
        String text=String.join("\n",Arrays.copyOfRange(source,first,last+1));
        Map<String,Object> e=new LinkedHashMap<>(); e.put("line",first+1); e.put("end",last+1);
        e.put("code",text.length()>1600?text.substring(0,1600)+"…":text); return e;
    }
}
