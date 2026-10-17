// 已确认的认证初值供部署配置和后端测试对照；运行时覆盖不能改变用途和单次消费规则。
module.exports={
  configurable:true,codeDigits:6,codeTtlSeconds:600,maxWrongAttempts:5,cooldownSeconds:60,
  emailHourlyLimit:5,emailDailyLimit:20,ipHourlyLimit:30,ipDailyLimit:100,
  accessTtlSeconds:900,refreshTtlSeconds:2592000,guestTtlSeconds:2592000,
};
