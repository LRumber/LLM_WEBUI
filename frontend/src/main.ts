import { createApp } from 'vue'
import 'element-plus/es/components/select/style/css'
import App from './App.vue'
import './styles.css'

// Component styles are imported on demand; the application root remains a standard Vue mount.
createApp(App).mount('#app')
