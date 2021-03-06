#!/usr/bin/bash

. VARS.sh

OUT=$UC_OUT

GUTS=$OUT/cat-guts
CATALOG=$OUT/catalog.xml

NB_VERSION=NetBeans-7.0

#JVI_MAIN=/a/src/jvi-dev/rel/nbvi-1.4/updates.xml 
#JVI_UC=/a/src/nbm/jvi.uc.nb70/build/updates.xml
#UC_DIR=UC
#JVI_ADD=" /a/src/nbm/editor.pin/build/updates.xml "

JVI_MAIN=/a/src/jvi-dev/nbvi/build/updates/updates.xml 
JVI_UC=/a/src/nbm/jvi.uc.nb70ea/build/updates.xml
UC_DIR=eaUC
JVI_ADD="
    /a/src/nbm/editor.pin/build/updates.xml
    "


mkdir -p $OUT
python combine_uc.py $JVI_MAIN $JVI_UC $JVI_ADD $OUT

echo '<?xml version="1.0" encoding="UTF-8"?>' > $CATALOG
echo '<!DOCTYPE module_updates PUBLIC "-//NetBeans//DTD Autoupdate Catalog 2.6//EN" "http://www.netbeans.org/dtds/autoupdate-catalog-2_6.dtd">' >> $CATALOG

cat $GUTS >> $CATALOG
rm $GUTS

# keep both files around since there is an update center out there
# that references the non-gz version
gzip -9 -c $CATALOG > $CATALOG.gz


# don't abort on error since the cmp might fail
set +e

cd $OUT

for i in *
do
    echo ===== $i
    arg=""
    if [[ $i = ${CATALOG##*/}.gz ]]
    then
        # ignore the date 
        arg=-i8
    fi
    if ! cmp $arg $i $UC_MIRROR/$NB_VERSION/$UC_DIR/$i > /dev/null
    then
        echo "      ^ DIFFERS ^"
    fi
done
