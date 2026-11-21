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
    public PublicNameController(PublicNames names) { this.names=names; }
    @GetMapping public CommonResult<String> mine() { return CommonResult.successResponse(names.mine(),"success"); }
    @PutMapping public CommonResult<String> save(@RequestBody Map<String,String> body) {
        return CommonResult.successResponse(names.save(body.get("displayName")),"success");
    }
    @ExceptionHandler(EduException.class)
    public CommonResult<Void> error(EduException e){return CommonResult.errorResponse(e.getMessage(),e.getStatus());}
}
