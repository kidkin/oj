package top.hcode.hoj.edu;

import java.util.Map;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.springframework.web.bind.annotation.*;
import top.hcode.hoj.common.result.CommonResult;

@RestController
@RequestMapping("/api/edu/display-name")
@RequiresAuthentication
public class PublicNameController {
    private final PublicNames names;
    private final RenameRequests requests;
    public PublicNameController(PublicNames names,RenameRequests requests) { this.names=names; this.requests=requests; }
    @GetMapping("/status") public CommonResult<Map<String,Object>> status(){
        return CommonResult.successResponse(requests.status());
    }
    @GetMapping("/queue") public CommonResult<java.util.List<Map<String,Object>>> queue(@RequestParam(required=false) Long classId,
            @RequestParam(defaultValue="0") long after){
        return CommonResult.successResponse(requests.queue(classId,after));
    }
    @PostMapping("/{id}/decision") public CommonResult<Boolean> decide(@PathVariable long id,
            @RequestParam(required=false) Long classId,@RequestParam boolean approve){
        requests.decide(id,classId,approve);
        return CommonResult.successResponse(true);
    }
    @GetMapping public CommonResult<String> mine() { return CommonResult.successResponse(names.mine(),"success"); }
    @PutMapping public CommonResult<String> save(@RequestBody Map<String,String> body) {
        return CommonResult.successResponse(requests.apply(body.get("displayName")),"success");
    }
    @ExceptionHandler(EduException.class)
    public CommonResult<Void> error(EduException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}
}
