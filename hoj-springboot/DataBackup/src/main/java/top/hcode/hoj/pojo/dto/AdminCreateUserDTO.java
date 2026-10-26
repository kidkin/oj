package top.hcode.hoj.pojo.dto;

import lombok.Data;
import lombok.ToString;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
@ToString(exclude = "password")
public class AdminCreateUserDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(max = 20, message = "用户名不能超过20位")
    @Pattern(regexp = "[^\\s@\\p{Z}\\p{Cc}]+", message = "用户名不能包含空白、控制字符或@")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度应为6~20位")
    private String password;

    @Size(max = 50, message = "真实姓名不能超过50位")
    private String realname;
}
