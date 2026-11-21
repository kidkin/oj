package top.hcode.hoj.edu;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.*;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Project identities only in read-only student-facing views. Never modify a cached rank or a stored judge record. */
@ControllerAdvice
public class PublicNameView implements ResponseBodyAdvice<Object> {
    private static final Set<String> PATHS = new HashSet<>(Arrays.asList("get-submission-list","get-submission-detail",
        "contest-submissions","check-submissions-status","check-contest-submissions-status","get-rank-list",
        "get-group-rank-list","get-contest-rank","get-current-week-ac-rank","get-recent-seven-ac-rank",
        "growth-rating","user-practice-levels","get-user-home-info","get-training-rank","get-contest-outside-info",
        "get-discussion-list","get-discussion-detail","comments","reply"));
    private final ObjectMapper mapper;
    private final PublicNames names;
    public PublicNameView(ObjectMapper mapper,PublicNames names){this.mapper=mapper;this.names=names;}
    @Override public boolean supports(MethodParameter method,Class<? extends HttpMessageConverter<?>> converter){
        return top.hcode.hoj.common.result.CommonResult.class.isAssignableFrom(method.getParameterType());
    }
    @Override public Object beforeBodyWrite(Object body,MethodParameter method,MediaType type,
            Class<? extends HttpMessageConverter<?>> converter,ServerHttpRequest req,ServerHttpResponse res){
        String path=req.getURI().getPath();
        if(body==null || !PATHS.contains(path.substring(path.lastIndexOf('/')+1))) return body;
        res.getHeaders().setCacheControl("private, no-store");
        JsonNode json=mapper.valueToTree(body);
        List<ObjectNode> rows=new ArrayList<>();
        collect(json,rows);
        Set<String> ids=new HashSet<>();
        for(ObjectNode row:rows) for(String key:Arrays.asList("uid","fromUid","toUid"))
            if(row.hasNonNull(key)) ids.add(row.get(key).asText());
        Map<String,String> labels=names.labels(ids,"roster".equals(req.getHeaders().getFirst("X-HOJ-Names")) && PublicNames.staff());
        for(ObjectNode row:rows) {
            String name=labels.get(row.path("uid").asText());
            for(String side:Arrays.asList("from","to")) {
                String label=labels.get(row.path(side+"Uid").asText());
                if(label!=null && row.has(side+"Name")) row.put(side+"Name",label);
            }
            if(name==null) continue;
            for(String key:Arrays.asList("username","realname","nickname","rankShowName","author"))
                if(row.has(key)) row.put(key,name);
        }
        return json;
    }
    private static void collect(JsonNode node,List<ObjectNode> rows){
        if(node.isObject() && ((node.hasNonNull("uid") && (node.has("username") || node.has("author") || node.has("realname"))) || node.hasNonNull("fromUid") || node.hasNonNull("toUid"))) rows.add((ObjectNode)node);
        if(node.isContainerNode()) for(JsonNode child:node) collect(child,rows);
    }
}
