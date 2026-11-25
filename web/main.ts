import { createApp } from 'vue';
import IdentityPage from './IdentityPage.vue';
import './identity.css';

createApp(IdentityPage, { side: location.pathname === '/customer.html' ? 'customer' : 'staff' }).mount('#app');
