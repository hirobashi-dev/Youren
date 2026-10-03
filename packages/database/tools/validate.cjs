const {spawnSync}=require('node:child_process');
const path=require('node:path');
const result=spawnSync(process.execPath,[require.resolve('prisma/build/index.js'),'validate','--schema',path.join(__dirname,'../prisma/schema.prisma')],{stdio:'inherit',env:{...process.env,DATABASE_URL:'postgresql://validation:validation@127.0.0.1:5432/validation'}});
process.exit(result.status??1);
