import { createApp } from 'vue';
import IdentityPage from './IdentityPage.vue';
import GuestPage from './GuestPage.vue';
import './identity.css';

const app = location.pathname.startsWith('/embed/')
  ? createApp(GuestPage)
  : createApp(IdentityPage, { side: location.pathname === '/customer.html' ? 'customer' : 'staff' });
app.mount('#app');
