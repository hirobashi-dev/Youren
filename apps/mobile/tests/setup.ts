// 组件测试固定安全区，原生屏幕的真实布局另用设备验收。
import mockSafeAreaContext from 'react-native-safe-area-context/jest/mock';
jest.mock('react-native-safe-area-context', () => mockSafeAreaContext);
