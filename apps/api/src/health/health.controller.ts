// 健康响应仅包含安全状态，不复制数据库连接串或异常。
import { Controller, Get, ServiceUnavailableException } from '@nestjs/common';
import { HealthService } from './health.service';
@Controller('health')
export class HealthController {
  constructor(private readonly service: HealthService) {}
  @Get('live') live() {
    return { status: 'ok' };
  }
  @Get('ready') async ready() {
    if (!(await this.service.isReady()))
      throw new ServiceUnavailableException({ status: 'unavailable' });
    return { status: 'ok' };
  }
}
