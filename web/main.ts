import { createApp } from 'vue';
import { ElAvatar, ElButton, ElCheckbox, ElCheckboxGroup, ElIcon, ElImageViewer, ElInput, ElOption, ElSelect, ElSwitch, ElTable, ElTableColumn, ElTag, ElTimeline, ElTimelineItem } from 'element-plus';
import 'element-plus/es/components/avatar/style/css';
import 'element-plus/es/components/button/style/css';
import 'element-plus/es/components/checkbox/style/css';
import 'element-plus/es/components/icon/style/css';
import 'element-plus/es/components/image-viewer/style/css';
import 'element-plus/es/components/input/style/css';
import 'element-plus/es/components/message-box/style/css';
import 'element-plus/es/components/option/style/css';
import 'element-plus/es/components/select/style/css';
import 'element-plus/es/components/switch/style/css';
import 'element-plus/es/components/table/style/css';
import 'element-plus/es/components/tag/style/css';
import 'element-plus/es/components/timeline/style/css';
import IdentityPage from './IdentityPage.vue';
import GuestPage from './GuestPage.vue';
import './identity.css';

const app = location.pathname.startsWith('/embed/')
  ? createApp(GuestPage)
  : createApp(IdentityPage, { side: location.pathname === '/customer.html' ? 'customer' : 'staff' });
for (const component of [ElAvatar, ElButton, ElCheckbox, ElCheckboxGroup, ElIcon, ElImageViewer, ElInput, ElOption, ElSelect, ElSwitch, ElTable, ElTableColumn, ElTag, ElTimeline, ElTimelineItem]) {
  app.use(component);
}
app.mount('#app');
